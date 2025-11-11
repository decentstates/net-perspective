(ns prspct.test-utils
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]
    [clojure.test :refer [deftest is testing]]

    [taoensso.telemere :as tel]

    [malli.instrument :as mi]))

(tel/set-min-level! :warn)

(defn ns-schemas-check []
  ;; NOTE: Can change the reporting this produces to match clojure.test
  (mi/check {:filters [(fn [n s _d]
                         (and (= (str n) (str/replace (str *ns*) "-test" ""))
                              (not (str/ends-with? (str s) "!"))))]}))

(defmacro deftest-ns-schemas-test []
  `(deftest ~'ns-schemas-test
     (testing "function schema checks"
       (let [res# (~#'ns-schemas-check)]
         (if res#
           (pprint res#))
         (is (not res#)))))) 

(defmacro with-out-data-map
  [& body]
  `(let [s-out# (new java.io.StringWriter)
         s-err# (new java.io.StringWriter)]
     (binding [
               *out* s-out#
               *err* s-err#]
       (let [r# (do ~@body)]
         {:res r#
          :out (str s-out#)
          :err (str s-err#)}))))
