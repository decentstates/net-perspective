(ns prspct.dsl
  (:require 
    [clojure.pprint :refer [pprint]])
  (:refer-clojure :exclude [-> ->>]))

(defn ctx [& more]
  (apply list 'ctx more))

(defn -> [& more]
  (apply list '-> more))

(defn ->> [& more]
  (apply list '->> more))

(defn write-contexts [f dsl-contexts]
  (doseq [dsl-context dsl-contexts]
    (spit f
          (with-out-str 
            (binding [clojure.pprint/*print-right-margin* 120] 
             (pprint dsl-context))))))

(defn write-config [f config]
  (spit f
        (with-out-str 
          (binding [clojure.pprint/*print-right-margin* 120] 
           (pprint config)))))

