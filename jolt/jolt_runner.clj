(ns jolt-runner
  "Runs the suite under Jolt.

  Discovers every test namespace beneath a root directory, requires them, and
  runs them through clojure.test in one process.

  This lives outside `test/` on purpose. Babashka's runner matches
  `:patterns [\".*\"]` and Basilisp's file pattern is `.*\\.(lpy|cljc)`, so a
  helper namespace dropped into `test/` gets loaded by other dialects too."
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

(defn -main [& [root]]
  (let [root (or root "test")
        nses (test-namespaces root)]
    (doseq [n nses]
      (require n))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
