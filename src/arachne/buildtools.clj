(ns arachne.buildtools
  "Tools for building the Arachne project itself."
  {:boot/export-tasks true}
  (:refer-clojure :exclude [test])
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [boot.core :as b]
            [boot.task.built-in :as task]
            [boot.util :as bu]
            [boot.pod :as pod]
            [adzerk.boot-test :as boot-test]
            [arachne.buildtools.git :as g]))

(defn- dev-dep?
  [dep]
  (some #(= [:scope "test"] %) (partition 2 1 dep)))

(defn- generate-project-clj!
  []
  (let [proj-file (io/file "project.clj")
        pom (:task-options (meta #'task/pom))
        head (list 'defproject
               (:project pom)
               (:version pom)
               :dependencies (vec
                               (filter (complement dev-dep?)
                                 (b/get-env :dependencies)))
               :source-paths (set (b/get-env :resource-paths))
               :test-paths #{"test" "dev"}
               :profiles {:dev {:dependencies (vec
                                                (filter dev-dep?
                                                  (b/get-env :dependencies)))
                                :source-paths #{"dev"}}})
        txt (bu/pp-str head)
        txt (str ";; WARNING: this file is automatically generated for Cursive compatibility\n ;; Edit project.edn or build.boot if you want to modify the real configuration\n" txt)]
    (println "Regenerating project.clj file for " (:project pom) (:version pom))
    (spit proj-file txt)))

(defn left-pad
  "Pad a string to be at least n characters, using c as the padding character"
  [s n c]
  (let [s (str s)
        delta (- n (count s))]
    (if (pos? delta)
      (apply str (concat (repeat delta c) [s]))
      s)))

(defn- set-pom-options!
  "Imperatively set the pom task options"
  [{:keys [project version description license]}]
  (let [{:keys [major minor patch qualifier]} version]
    (b/task-options!
     task/pom
     {:project project
      :description description
      :license license
      :version (str major "."
                    minor "."
                    patch (cond
                           (= :dev qualifier) (str "-" (g/current-branch ".")
                                                   "-" (left-pad
                                                         (g/current-loglenth ".")
                                                         4 "0")
                                                   "-" (g/current-sha "."))
                           qualifier (str "-" qualifier)
                           :else ""))})))

(defn- local-path
  "Given a dependency, return the local path (if there is one)"
  [dep]
  (some (fn [[key val]]
          (when (= :local key) val))
    (partition 2 1 dep)))

(defn- process-dep
  "Given a dependency form from project.edn, update any git repositories and
  return the standard dependency vector"
  [[name version :as dep]]
  (if (vector? version)
    [name (g/ensure-installed! dep)]
    dep))

(defn- process-deps
  "Process dependencies into the canonical format"
  [deps]
  (doall (filter (fn [[artifact _ :as dep]]
                   (and dep
                     (not= artifact 'org.arachne-framework/arachne-buildtools)))
           (map process-dep deps))))

(declare resource-paths)
(defn- local-dep-paths
  "Given the relative path of another project, return *its* resource-paths"
  [project-location]
  (let [here (io/file ".")
        there (io/file here project-location)
        proj-file (io/file there "project.edn")
        proj-data (edn/read-string (slurp proj-file))
        dep-paths (resource-paths proj-data)]
    (map (fn [dep-path]
           (.getPath (io/file there dep-path)))
      dep-paths)))

(defn- resource-paths
  "Determine the correct resource paths for this project, including local
  dependencies on other projects using the buildtools."
  [proj-data]
  (set (reduce (fn [paths dep]
                 (if-let [proj-path (local-path dep)]
                   (concat paths (local-dep-paths proj-path))
                   paths))
         #{"src" "resources"}
         (:deps proj-data))))

(defn- set-env!
  "Sets the boot environment. Hardcodes things that will be common across all
  Arachne projects."
  [proj-data]
  (b/set-env!
    :resource-paths (resource-paths proj-data)
    :source-paths #{"test" "dev"}
    :dependencies #(process-deps (concat % (:deps proj-data)))))

(defn read-project-edn!
  "Imperatively read the project.edn file and use it to set env and
  task options. Every time this is run, it will also spit out an
  updated project.clj file."
  []
  (let [filename "project.edn"
        proj-file (io/file filename)]
    (when-not (.exists proj-file)
      (throw (ex-info (format "Could not find file %s in process directory (%s)"
                              filename
                              (.getCanonicalPath (io/file ".")))
                      {:filename filename})))
    (let [proj-data (edn/read-string (slurp proj-file))]
      (set-pom-options! proj-data)
      (set-env! proj-data)
      (generate-project-clj!))))

(b/deftask print-version
  "Print out the artifact version based on the current POM configuration"
  []
  (println "Installed Version:" (:version (:task-options (meta #'task/pom))))
  identity)

(defn- throw-if-local-deps
  "Throw an exception if the project.edn file specifies any local dependencies"
  []
  (let [proj-file (io/file "project.edn")
        proj-data (edn/read-string (slurp proj-file))]
    (doseq [dep (:deps proj-data)]
      (when (local-path dep)
        (throw (ex-info
                 (format "Cannot build: project.edn has a local dependency, %s"
                   dep)
                 {:dep dep}))))))

(b/deftask build
  "Build the project and install to local maven repo"
  []
  (throw-if-local-deps)
  (g/throw-if-not-clean "." "Cannot build: git repository has uncommitted changes")
  (comp (task/pom) (task/jar) (task/install) (print-version)))


(b/deftask test
  "Run the project's unit tests"
  [n namespaces NAMESPACE #{sym} "The set of namespace symbols to run tests in."
   e exclusions NAMESPACE #{sym} "The set of namespace symbols to be excluded from test."
   f filters    EXPR      #{edn} "The set of expressions to use to filter namespaces."
   r requires   REQUIRES  #{sym} "Extra namespaces to pre-load into the pool of test pods for speed."
   j junit-output-to JUNITOUT str "The directory where a junit formatted report will be generated for each ns"

   i integration          bool   "Run only integration tests"
   a all                  bool   "Run all tests (integration and unit)"]
  (apply boot-test/test
    (apply concat
      (-> *opts*
        (update :filters (fn [fs]
                           (cond
                             all fs
                             integration (conj fs '(:integration (meta %)))
                             :else (conj fs '(not (:integration (meta %)))))))
        (dissoc :all :integration)))))

(use 'clojure.pprint)

(b/deftask run
  "Run a specific function in a pod, terminating when the function returns"
  [f function FUNCTION sym "The function to execute."
   a args     ARGS     #{str} "Arguments to pass to the specified function."]
  (let [pod (pod/make-pod (b/get-env))
        fn-ns (symbol (namespace function))]
    (try
      (pod/with-eval-in pod
        (require (quote ~fn-ns))
        (apply ~function ~args))
      (finally
        (pod/destroy-pod pod))))
  identity)

(b/deftask service
  "Run a specific function in a pod, keeping the JVM running (until it recieves a kill signal)"
  [f function FUNCTION sym "The function to execute."
   a args     ARGS     #{str} "Arguments to pass to the specified function."]
  (let [pod (pod/make-pod (b/get-env))
        fn-ns (symbol (namespace function))]
    (try
      (pod/with-eval-in pod
        (require (quote ~fn-ns))
        (apply ~function ~args)
        (while true
          (Thread/sleep 1000)))
      (finally
        (pod/destroy-pod pod))))
  identity)

(defn- throw-if-not-dev
  "Throw an exception if the project version doesn't have a :dev qualifier"
  []
  (let [filename "project.edn"
        proj-file (io/file filename)
        proj (edn/read-string (slurp proj-file))]
    (when-not (= :dev (-> proj :version :qualifier))
      (throw (ex-info (format "Can only do a dev deploy for a version ID with {:qualifier :dev}") {})))))

(b/deftask deploy-dev
  "Build the project and install to Arachne's dev Artifactory repository"
  []
  (throw-if-local-deps)
  (throw-if-not-dev)
  (g/throw-if-not-clean "." "Cannot build: git repository has uncommitted changes")
  (comp (task/pom)
        (task/jar)
        (task/push :repo "arachne-dev")
        (print-version)))