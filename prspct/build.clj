(ns build
  (:refer-clojure :exclude [test])
  (:require 
    [clojure.java.shell :as shell]
    [clojure.tools.build.api :as b]))

(def lib 'org.net-perspective/prspct)
(def version "0.1.0-SNAPSHOT")
(def main 'prspct.core)
(def class-dir "target/classes")

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test]})
        cmds     (b/java-command
                  {:basis     basis
                   :main      'clojure.main
                   :main-args ["-m" "kaocha.runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- uber-opts [opts]
  (assoc opts
         :lib lib :main main
         :uber-file (format "target/%s-%s.jar" lib version)
         :native-image-file (format "target/%s-%s" lib version)
         :basis (b/create-basis {})
         :class-dir class-dir
         :src-dirs ["src"]
         :ns-compile [main]))

(defn native-image [opts]
  (let [ret (shell/sh "native-image" 
                      "-jar" (:uber-file opts) 
                      "-o" (:native-image-file opts)
                      "-Os" ;; Testing smaller file size
                      "--features=clj_easy.graal_build_time.InitClojureClasses"
                      "--initialize-at-build-time=org.slf4j.helpers.NOPLoggerFactory"
                      "--initialize-at-build-time=java.time.Instant"
                      "-classpath" (java.lang.System/getProperty "java.class.path"))]
    (print (:out ret))
    (print (:err ret)))
  opts)

(defn ci-light "Run the CI pipeline of tests (and build the uberjar,) minus native-image." [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (uber-opts opts)]
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println (str "\nCompiling " main "..."))
    (b/compile-clj opts)
    (println "\nBuilding JAR...")
    (b/uber opts)
    opts))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (let [opts (ci-light opts)]
    (println "\nCompiling native-image... (This will take a few minutes...)")
    (native-image opts)
    ;; TODO: Perform integration tests with the final executable
    opts))
