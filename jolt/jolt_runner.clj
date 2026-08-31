(ns jolt-runner
  "Runs the suite under Jolt.

  Discovers every test namespace beneath a root directory, requires them, and
  runs each through clojure.test.

  This lives outside `test/` on purpose. Babashka's runner matches
  `:patterns [\".*\"]` and Basilisp's file pattern is `.*\\.(lpy|cljc)`, so a
  helper namespace dropped into `test/` gets loaded by other dialects too.

  Usage:

    jolt -M:jolt                 run everything, print failures, exit 1 if any
    jolt -M:jolt --report        additionally emit machine-readable CTS lines

  Output from a namespace with no failures is swallowed, so what reaches the
  log is the detail you actually need. `--report` adds one

    CTS <namespace> <pass> <fail> <error>

  line per namespace, which `jolt/compare_baseline.clj` diffs against
  `jolt/baseline.txt` to tell a regression from an improvement."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- path->ns
  "test/clojure/core_test/update.cljc -> clojure.core-test.update"
  [root path]
  (-> (subs path (inc (count root)))
      (str/replace #"\.cljc$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn test-namespaces
  "Every `.cljc` namespace under `root`, minus the helpers that define no tests.
  `portability` is excluded because it installs an `assert-expr` method rather
  than tests; it still gets loaded, as a dependency of the namespaces that use
  `p/thrown?`."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (map #(.getPath %))
       (filter #(str/ends-with? % ".cljc"))
       (map #(path->ns root %))
       (remove #(str/ends-with? % ".portability"))
       sort
       (map symbol)))

(defn- run-one
  "Run one namespace with its output captured. clojure.test writes through
  `*test-out*`, so binding `*out*` alone would not catch it."
  [n]
  (let [w (java.io.StringWriter.)
        r (binding [*out* w t/*test-out* w] (t/run-tests n))]
    {:ns n
     :pass (:pass r 0)
     :fail (:fail r 0)
     :error (:error r 0)
     :out (str w)}))

(defn -main [& argv]
  (let [flags (set (filter #(str/starts-with? % "--") argv))
        root (or (first (remove #(str/starts-with? % "--") argv)) "test")
        nses (test-namespaces root)]
    (doseq [n nses]
      (require n))
    (let [results (mapv run-one nses)
          {:keys [pass fail error]}
          (reduce (fn [acc r] (merge-with + acc (select-keys r [:pass :fail :error])))
                  {:pass 0 :fail 0 :error 0}
                  results)]
      ;; Only the namespaces that went wrong get their output printed. A green
      ;; run is 248 silent namespaces and one summary line.
      (doseq [{:keys [out] :as r} results]
        (when (pos? (+ (:fail r) (:error r)))
          (print out)
          (flush)))
      (when (flags "--report")
        (println)
        (doseq [{:keys [ns pass fail error]} results]
          (println "CTS" ns pass fail error)))
      (println)
      (println (format "TOTAL %d namespaces, %d assertions passed, %d failures, %d errors"
                       (count nses) pass fail error))
      (System/exit (if (zero? (+ fail error)) 0 1)))))
