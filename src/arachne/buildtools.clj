(ns arachne.buildtools
  "Tools for building the Arachne project itself."
  {:boot/export-tasks true}
  (:import [java.io File])
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [boot.core :as b]
            [boot.task.built-in :as task]
            [boot.util :as bu]
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
               :source-paths (set (concat (b/get-env :source-paths)
                                          (b/get-env :resource-paths)))
               :test-paths #{"test" "dev"}
               :profiles {:dev {:dependencies (vec
                                                (filter dev-dep?
                                                  (b/get-env :dependencies)))
                                :source-paths #{"dev"}}})
        txt (bu/pp-str head)
        txt (str ";; WARNING: this file is automatically generated for Cursive compatibility\n ;; Edit project.edn or build.boot if you want to modify the real configuration\n" txt)]
    (println "Regenerating project.clj file for " (:project pom) (:version pom))
    (spit proj-file txt)))

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
                           (= :dev qualifier) (str "-dev-" (g/current-sha "."))
                           qualifier (str "-" qualifier)
                           :else ""))})))

(defn- local-dep?
  "Given a dependency version string, return true if it looks like a local
  dependency reference"
  [version]
  (and (string? version)
    (not (re-matches #"^[0-9]+\..+" version))))

(declare process-deps)

(defn- transitive-deps
  "Get the transitive dependencies of a local dependency"
  [project-location]
  (let [here (File. ".")
        there (File. here project-location)
        proj-file (File. there "project.edn")
        proj-data (edn/read-string (slurp proj-file))
        trans-deps (filter (fn [dep]
                             (not-any? #(= :scope %) dep))
                     (:deps proj-data))]
    (process-deps trans-deps)))

(defn- process-dep
  "Given a dependency form from project.edn:

   - If it's a git dependnecy, clone/update and install
   - If it's a local dep, swap in the transitive deps
   - If it's a vanilla artifact dependency, no-op

   No matter what, always return a *seq* of deps."
  [[name version :as dep]]
  (if (string? version)
    (if (local-dep? version)
      (transitive-deps version)
      [dep])
    [[name (g/ensure-installed! dep)]]))

(defn- process-deps
  "Process dependencies into the canonical format"
  [deps]
  (filter (fn [[artifact _ :as dep]]
            (and dep
              (not= artifact 'org.arachne-framework/arachne-buildtools)))
    (mapcat process-dep deps)))

(declare resource-paths)
(defn- local-dep-paths
  "Given the relative path of another project, return *its* resource-paths"
  [project-location]
  (let [here (File. ".")
        there (File. here project-location)
        proj-file (File. there "project.edn")
        proj-data (edn/read-string (slurp proj-file))
        dep-paths (resource-paths proj-data)]
    (map (fn [dep-path]
           (.getPath (File. there dep-path)))
      dep-paths)))

(defn- resource-paths
  "Determine the correct resource paths for this project, including local
  dependencies on other projects using the buildtools."
  [proj-data]
  (set (reduce (fn [paths [_ version :as dep]]
                 (if (local-dep? version)
                   (concat paths (local-dep-paths version))
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
    (doseq [[name version] (:deps proj-data)]
      (when (local-dep? version)
        (throw ex-info
          (format "Cannot build: project.edn has a local dependency, [%s %s]"
            name version)
          {:dep-name name
           :dep-version version})))))

(b/deftask build
  "Build the project and install to local maven repo"
  []
  (throw-if-local-deps)
  (g/throw-if-not-clean "." "Cannot build: git repository has uncommitted changes")
  (comp (task/pom) (task/jar) (task/install) (print-version)))

