(ns compare-baseline
  "Diffs a `jolt -M:jolt --report` run against `jolt/baseline.txt`.

  Jolt does not pass this suite and is not expected to. Most of what fails is
  a deliberate divergence: one exact-integer type, one flonum type, a vendored
  regex engine, no JVM arrays. So a plain pass/fail gate says nothing useful
  during Jolt development, and the question worth answering on every push is
  which namespaces MOVED.

  Exits 1 only on a regression. An improvement is reported loudly, with the
  line to paste into the baseline, but it does not fail the run: a Jolt change
  that fixes something should not look like a breakage."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- parse-baseline
  "<namespace> <fail> <error>, # comments and blanks ignored. A namespace that
  is absent is expected to be clean."
  [path]
  (if-not (.exists (io/file path))
    {}
    (into {}
          (for [line (str/split-lines (slurp path))
                :let [line (str/trim line)]
                :when (and (seq line) (not (str/starts-with? line "#")))
                :let [[ns f e] (str/split line #"\s+")]
                :when (and ns f e)]
            [ns [(parse-long f) (parse-long e)]]))))

(defn- parse-report
  "CTS <namespace> <pass> <fail> <error> lines from the runner."
  [path]
  (into {}
        (for [line (str/split-lines (slurp path))
              :when (str/starts-with? line "CTS ")
              :let [[_ ns p f e] (str/split line #"\s+")]]
          [ns {:pass (parse-long p) :fail (parse-long f) :error (parse-long e)}])))

(defn- classify [baseline current]
  (let [names (sort (into (set (keys current)) (keys baseline)))]
    (for [n names
          :let [{:keys [fail error] :or {fail 0 error 0}} (get current n)
                [bf be] (get baseline n [0 0])
                gone? (not (contains? current n))]
          :when (or gone? (not= [fail error] [bf be]))]
      {:ns n
       :now [fail error]
       :was [bf be]
       :kind (cond gone? :gone
                   (or (> fail bf) (> error be)) :regression
                   :else :improvement)})))

(defn- row [{:keys [ns now was]}]
  (format "| `%s` | %d / %d | %d / %d |" ns (first was) (second was) (first now) (second now)))

(defn -main [& [report-path baseline-path]]
  (let [current (parse-report (or report-path "report.txt"))
        baseline (parse-baseline (or baseline-path "jolt/baseline.txt"))
        changes (classify baseline current)
        by-kind (group-by :kind changes)
        totals (reduce (fn [a [_ v]] (merge-with + a (select-keys v [:pass :fail :error])))
                       {:pass 0 :fail 0 :error 0} current)
        {:keys [pass fail error]} totals
        assertions (+ pass fail error)
        regressions (:regression by-kind)
        gone (:gone by-kind)
        improvements (:improvement by-kind)]

    (println "## Jolt conformance")
    (println)
    (println (format "**%d assertions passed**, %d failed, %d errored, across %d namespaces."
                     pass fail error (count current)))
    (when (pos? assertions)
      (println)
      (println (format "Parity: **%.2f%%** of %d assertions."
                       (double (* 100 (/ pass assertions))) assertions)))
    (println)

    (when (seq regressions)
      (println (format "### %d regression(s)" (count regressions)))
      (println)
      (println "| namespace | was fail/error | now fail/error |")
      (println "| :--- | ---: | ---: |")
      (doseq [c regressions] (println (row c)))
      (println))

    (when (seq gone)
      (println (format "### %d baseline entr(ies) for a namespace that no longer reports"
                       (count gone)))
      (println)
      (println "Either the namespace was removed upstream or it failed to load.")
      (println)
      (doseq [c gone] (println (format "- `%s`" (:ns c))))
      (println))

    (when (seq improvements)
      (println (format "### %d improvement(s)" (count improvements)))
      (println)
      (println "Not a failure. Update `jolt/baseline.txt` in the same change that caused it:")
      (println)
      (println "| namespace | was fail/error | now fail/error |")
      (println "| :--- | ---: | ---: |")
      (doseq [c improvements] (println (row c)))
      (println)
      (println "<details><summary>new baseline lines</summary>")
      (println)
      (println "```")
      (doseq [{:keys [ns now]} improvements
              :when (pos? (+ (first now) (second now)))]
        (println ns (first now) (second now)))
      (println "```")
      (println)
      (println "</details>")
      (println))

    (when (empty? changes)
      (println "No namespace moved. Matches the baseline exactly."))

    (println)
    (println (format "<sub>Regenerate the baseline with `bb jolt-baseline`.</sub>"))

    (System/exit (if (or (seq regressions) (seq gone)) 1 0))))
