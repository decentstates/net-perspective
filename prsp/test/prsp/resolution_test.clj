(ns prsp.resolution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.generators :as gen]
   [com.gfredericks.test.chuck.generators :as gen']
   [com.gfredericks.test.chuck.clojure-test :refer [checking]]

   [taoensso.telemere :as tel]


   [prsp.schemas :as ps]
   [prsp.test-utils]
   [prsp.resolution :as sut])
  (:import
   [java.time Instant]))

(prsp.test-utils/deftest-ns-schemas-test)

(deftest resolve-config-test
  (testing "basics no publication-messages"
    (let [dsl
          '[(ctx "#"
                 (ctx "self.contacts"
                      (ctx "d"
                           (-> "email:d@test.com" "#self" :public)))
                 (ctx "food"
                      (-> "geo:37.786971,-122.399677" "#null" :public))
                 (ctx "friends"
                      (-> :</self.contacts.d "#null" :public)))]

          user-config
          {:user-contexts
           (ps/user-relations-dsl->user-config dsl)}

          now-instant
          (Instant/now)

          res
          (sut/resolve-config user-config now-instant [])

          expected
          {:working-contexts
           [{:context []
             :context-config {}
             :relations []}
            {:context ["self" "contacts"]
             :context-config
             {}
             :relations []}
            {:context ["self" "contacts" "d"]
             :context-config {}
             :relations
             [#:relation{:object-pair ["email:d@test.com" ["self"]]
                         :transitive? false,
                         :public? true}]}
            {:context ["food"]
             :context-config {}
             :relations
             [#:relation{:object-pair
                         ["geo:37.786971,-122.399677" ["null"]]
                         :transitive? false,
                         :public? true}]}
            {:context ["friends"]
             :context-config {}
             :relations
             [#:relation{:object-pair [:</self.contacts.d ["null"]]
                         :transitive? false,
                         :public? true}
              #:relation{:object-pair ["email:d@test.com" ["null"]]
                         :transitive? false,
                         :public? true}]}]
           :resolved-self-contexts
           {["self" "contacts" "d"]
            #{["email:d@test.com" ["self"]]},
            ["food"]
            #{["geo:37.786971,-122.399677" ["null"]]},
            ["friends"]
            #{[:</self.contacts.d ["null"]] ["email:d@test.com" ["null"]]}}}]

      (is (= expected
             (select-keys res [:resolved-self-contexts :working-contexts]))))))

(comment
  (tel/with-min-level :info
    (resolve-config-test)))
