(ns prspct.schemas-test
  (:require 
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]
    [com.gfredericks.test.chuck.generators :as gen']

    [malli.core :as m]
    [malli.error :as me]
    [malli.generator :as mg]
    [malli.instrument :as mi]

    [prspct.schemas :as ps]
    [prspct.test-utils]))

(prspct.test-utils/deftest-ns-schemas-test)

(def CustomGenSchema
  "Schema for a Schema with custom generator"
  [:cat 
   :keyword 
   [:and 
    [:map]
    [:fn (fn [m] 
           (let [namespaces (->> m keys (map namespace) set)]
             (contains? namespaces "gen")))]]
   [:* :any]]) 

(defn- is-custom-gen-schema [x]
  (m/validate CustomGenSchema x))

(def -gen-test-suffix "-gen-test")
(defn create-schema-gen-tests 
  "Creates tests to ensure custom generators match the schemas"
  [namespace]
  {:removed
    (doall
     (for [test-symbol (filterv (fn [k] (str/ends-with? (str k) -gen-test-suffix))
                                (keys (ns-publics *ns*)))]
        (do
          (ns-unmap *ns* test-symbol) 
          (symbol (str *ns*) (str test-symbol)))))

   :added
   (doall
     (for [[schema-name schema] 
           (filterv (fn [[_ v]] (is-custom-gen-schema (deref v))) 
                    (ns-publics namespace))]
         (eval `(deftest ~(symbol (str schema-name -gen-test-suffix))
                  (checking "schema generation produces conforming data"
                            [v# (mg/generator ~schema)]
                            (is (nil? (me/humanize (m/explain ~schema v#)))))))))})

(create-schema-gen-tests 'prspct.schemas)

(deftest simple-message-test
  (checking "symmetry simple-message->eml->simple-message"
            [msg (mg/generator ps/SimpleMessage)]
            (is (= msg (#'ps/eml->simple-message
                         (#'ps/simple-message->eml msg)))))
  (testing "basic message"
    (let [basic-message  "from:me\r\nto:you\r\n\r\nhello"]
      (is (= {:headers {"from" "me"
                        "to" "you"}
              :body "hello"}
             (#'ps/eml->simple-message basic-message))))))

(deftest edn-message-test
  (checking "symmetry edn->simple-message->edn"
            [schema (gen/elements
                      [ps/PublicationMessage])
                      
             msg (mg/generator schema)]
            (is (= msg (#'ps/simple-message->edn-message
                         schema 
                         (#'ps/edn-message->simple-message schema msg))))))
