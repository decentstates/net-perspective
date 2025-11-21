(ns prspct.resolution-test
  (:require 
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.generators :as gen']
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]

    [malli.core :as m]
    [malli.generator :as mg]

    [prspct.schemas :as ps]
    [prspct.test-utils]
    [prspct.resolution :as sut]))

(prspct.test-utils/deftest-ns-schemas-test)

(deftest resolve-config-test
  (testing "basics no publication-messages"
    (let [dsl 
          '[(ctx "#" {:np.contacts/configs [{:ctx "#self.contacts"
                                               :under-namespace "contacts"}]}
                   (ctx "self.contacts" {}
                        (ctx "d" {}
                             (-> "email:d@test.com" "#self")))
                   (ctx "food" {}
                        (-> "uri:geo:37.786971,-122.399677" "#null"))
                   (ctx "friends" {}
                        (-> :contacts/d "#null")))]

           user-config 
           (ps/user-config-dsl->user-config dsl)

           res 
           (sut/resolve-config user-config [])

           expected
           {:working-contexts
            [{:context []
              :config
              #:np.contacts{:configs
                            [{:ctx "#self.contacts",
                              :under-namespace "contacts"}]},
              :relations []}
             {:context ["self" "contacts"]
              :config
              #:np.contacts{:configs
                            [{:ctx "#self.contacts",
                              :under-namespace "contacts"}]},
              :relations []}
             {:context ["self" "contacts" "d"]
              :config
              #:np.contacts{:configs
                            [{:ctx "#self.contacts",
                              :under-namespace "contacts"}]},
              :relations
              [#:relation{:object-pair ["email:d@test.com" ["self"]]
                          :transitive? false,
                          :public? true}]}
             {:context ["food"]
              :config
              #:np.contacts{:configs
                            [{:ctx "#self.contacts",
                              :under-namespace "contacts"}]},
              :relations
              [#:relation{:object-pair
                          ["uri:geo:37.786971,-122.399677" ["null"]]
                          :transitive? false,
                          :public? true}]}
             {:context ["friends"]
              :config
              #:np.contacts{:configs
                            [{:ctx "#self.contacts",
                              :under-namespace "contacts"}]},
              :relations
              [#:relation{:object-pair [:contacts/d ["null"]]
                          :transitive? false,
                          :public? true}
               #:relation{:object-pair ["email:d@test.com" ["null"]]
                          :transitive? false,
                          :public? true}]}]
            :resolved-contexts
            {["self" "contacts" "d"]
             #{["email:d@test.com" ["self"]]},
             ["food"] 
             #{["uri:geo:37.786971,-122.399677" ["null"]]},
             ["friends"]
             #{[:contacts/d ["null"]] ["email:d@test.com" ["null"]]}}}]
           
      ;; TODO: How can I refer to myself.
      (is (= expected
             (select-keys res [:resolved-contexts :working-contexts]))))))

(comment
  (meta #'resolve-config-test)
  (resolve-config-test)
  (:test (meta #'resolve-config-test)))
