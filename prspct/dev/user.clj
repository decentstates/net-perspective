(ns user
  (:require 
   [clojure.string]
   [clojure.java.io]
   [clojure.tools.namespace.repl]

   [taoensso.telemere :as tel]
   [malli.dev]))
   
   


(set! *warn-on-reflection* true)

(tel/add-handler! ::file-log (tel/handler:file))
(tel/remove-handler! :default/console)
(tel/add-handler! ::console (tel/handler:console {:output-fn (tel/format-signal-fn {:content-fn (fn [s] "")})}))
(tel/event! ::logging-initialized)
(tel/get-handlers)
(malli.dev/start!)


(do
  (in-ns 'clojure.core)

  (defn after-in-ns []
    (require '[clojure.pprint :refer [pprint print-table]])
    (require '[clojure.repl :as repl])
    (require '[clojure.tools.trace :refer [trace trace-vars untrace-vars]])
    (require '[sc.api :as scap])
    (require '[malli.generator :as mg])
    (require '[utils :refer :all])

    (require '[clojure.repl :refer (source apropos dir pst doc find-doc)]
             '[clojure.java.javadoc :refer (javadoc)]
             '[clojure.pprint :refer (pp pprint print-table)]
             '[clojure.repl.deps :refer (add-libs add-lib sync-deps)]))

  (defonce original-in-ns clojure.core/in-ns)
  (defn in-ns [ns-sym]
    (original-in-ns ns-sym)
    (when
      (or
        (clojure.string/starts-with? (str ns-sym) "user")
        (clojure.string/starts-with? (str ns-sym) "prspct"))
      (println (str "Altering namespace `" ns-sym "` with `after-in-ns`"))
      (after-in-ns)))
  (in-ns 'user))

(defn refresh []
  (clojure.tools.namespace.repl/refresh))

(comment
  (refresh))

(tel/event! ::finished-loading)
