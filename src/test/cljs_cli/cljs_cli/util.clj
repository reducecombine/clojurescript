(ns cljs-cli.util
  (:refer-clojure :exclude [*in*])
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.test :refer [is]]
   [cljs.repl :as repl])
  (:import
   (java.io File)
   (java.nio.file Files CopyOption)
   (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *repl-env* "node")
(def ^:dynamic *repl-env-filter* (constantly true))
(def ^:dynamic *repl-opts* nil)
(def ^:dynamic *sources* nil)
(def ^:dynamic *in* nil)
(def ^:dynamic *post-condition* nil)

(defmacro with-sources
  [sources & body]
  `(binding [*sources* ~sources]
     ~@body))


(defmacro with-in
  [in & body]
  `(binding [*in* ~in]
     ~@body))

(defmacro with-post-condition
  [post-condition & body]
  `(binding [*post-condition* ~post-condition]
     ~@body))

(defmacro with-repl-env-filter
  [repl-env-filter & body]
  `(binding [*repl-env-filter* ~repl-env-filter]
     ~@body))

(defn- ^File make-temp-dir []
  (.toFile (Files/createTempDirectory "cljs-cli-test" (make-array FileAttribute 0))))

(defn- delete-recursively [fname]
  (doseq [f (reverse (file-seq (io/file fname)))]
    (io/delete-file f)))

(defn- copy-uberjar [^File dest]
  (Files/copy (.toPath (io/file "target/cljs.jar")) (.toPath (io/file dest "cljs.jar")) ^"[Ljava.nio.file.CopyOption;" (make-array CopyOption 0)))

(defn- write-sources [temp-dir]
  (let [qualified #(io/file temp-dir %)]
    (run! #(io/make-parents (qualified %)) (keys *sources*))
    (run! (fn [[file source]]
            (spit (qualified file) source))
      *sources*)))

(defn- run-in-temp-dir [args]
  (let [temp-dir (make-temp-dir)]
    (try
      (write-sources temp-dir)
      (copy-uberjar temp-dir)
      (let [result (shell/with-sh-dir temp-dir
                     #_(apply println "running:" args)
                     (apply shell/sh (if *in* (concat args [:in *in*]) args)))]
        (when *post-condition*
          (is (*post-condition* temp-dir)))
        result)
      (finally
        (delete-recursively temp-dir)))))

(defn form-cp []
  (string/join File/pathSeparator ["cljs.jar" "src"]))

(defn cljs-main [& args]
  (if (*repl-env-filter* *repl-env*)
    (let [command-line-args (map str args)]
      (run-in-temp-dir
        (keep (fn [arg]
                (when arg
                  (str arg)))
          (into ["java" "-cp" (form-cp) "cljs.main"
                 "-re" *repl-env*
                 (when *repl-opts* "-ro") (when *repl-opts* *repl-opts*)]
            command-line-args))))
    {:exit 0 :out "" :err "" :repl-env-filtered true}))

(def ^:private expected-browser-err
  "Compiling client js ...\nServing HTTP on localhost port 9000\nListening for browser REPL connect ...\n")

(defn- maybe-print-result-err [{:keys [err]}]
  (when (and (not (empty? err))
             (not (= expected-browser-err err)))
    (binding [*out* *err*]
      (println err))))

(defn output-is [result & expected-lines]
  (is (zero? (:exit result)))
  (maybe-print-result-err result)
  (when-not (:repl-env-filtered result)
    (is (= (string/trim (apply str (map print-str (interleave expected-lines (repeat "\n")))))
          (string/trim (:out result))))))

(defn check-result [result pred]
  (when-not (:repl-env-filtered result)
    (pred result)))

(defn repl-title []
  (string/trim (with-out-str (repl/repl-title))))
