(ns prspct.cli-test
  (:require 
    [clojure.pprint :refer [pprint]]
    [clojure.java.io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.generators :as gen']
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]

    [malli.core :as m]
    [malli.error :as me]
    [malli.generator :as mg]
    [malli.instrument :as mi]

    [babashka.fs :as fs]

    [prspct.dsl :as dsl]
    [prspct.schemas :as ps]
    [prspct.test-utils]
    [prspct.lib.utils :as utils]
    [prspct.cli :as sut]))


(prspct.test-utils/deftest-ns-schemas-test)

(deftest init-test
  (testing "basics"
    (utils/with-temp-dir [base-dir {}]
      (sut/-main "init" "--base-dir" base-dir)
      (is (= (sort (vec (map str (file-seq (fs/file base-dir)))))
             (sort [(str base-dir "")
                    (str base-dir "/relations.edn")
                    (str base-dir "/config.edn")
                    (str base-dir "/.prspct")
                    (str base-dir "/.prspct/fetches")
                    (str base-dir "/.prspct/fetches/0")
                    (str base-dir "/.prspct/fetches/0/fetch-info.edn")
                    (str base-dir "/.prspct/.gitignore")
                    (str base-dir "/.prspct/fetches.HEAD")
                    (str base-dir "/.prspct/fetches.HEAD/fetch-info.edn")]))))))

(def ^:dynamic *no-delete-test-data* false)

(deftest integration-test
  (testing "basic roundtrip"
    (utils/with-temp-key-pairs [a-key-pair {:no-delete *no-delete-test-data*}
                                b-key-pair {:no-delete *no-delete-test-data*}
                                c-key-pair {:no-delete *no-delete-test-data*}]
      (utils/with-temp-dir [a-base-dir {:no-delete *no-delete-test-data*}
                            b-base-dir {:no-delete *no-delete-test-data*}
                            c-base-dir {:no-delete *no-delete-test-data*}
                            srv-dir {:no-delete *no-delete-test-data*}] 
        (when *no-delete-test-data*
          (println 'a-key-pair (str a-key-pair))
          (println 'a-base-dir (str a-base-dir))
          (println 'b-key-pair (str b-key-pair))
          (println 'b-base-dir (str b-base-dir))
          (println 'c-key-pair (str c-key-pair))
          (println 'c-base-dir (str c-base-dir))
          (println 'srv-dir (str srv-dir)))
        (sut/-main "init" "--base-dir" a-base-dir)
        (sut/-main "init" "--base-dir" b-base-dir)
        (sut/-main "init" "--base-dir" c-base-dir)
        (let [a-ident 
              (-> a-key-pair :public slurp ps/ssh-public-key->identifier-ssh)

              a-config-options-edn
              {:sources
               {:main-source 
                {:source/fn 'prspct.message-transfer/shell-source
                 :source/args
                 ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

               :publishers
               {:main-publisher
                {:publisher/fn 
                  'prspct.message-transfer/shell-publisher
                  :publisher/args 
                  ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" (str srv-dir) ";"]}}

               :publish-identities 
               {:main-identity
                {:name "Alice"
                 :email "alice@example.com"
                 :ssh-key-id/public-key-path (:public a-key-pair)
                 :ssh-key-id/private-key-path (:private a-key-pair)}}

               :publish-configs 
               {:main-config
                {:identity :main-identity
                 :publisher :main-publisher}}

               :default-publish-configs 
               [:main-config]}

              a-relations-edn 
              [(dsl/ctx "#" 
                        (dsl/ctx "private"
                                 (dsl/->> "uri:http://some-private.example.com/" "#null"))
                        (dsl/ctx "net-perspective"
                                 (dsl/-> "uri:https://net-perspective.org" "#net-perspective.*" :public)
                                 (dsl/-> "email:admin@net-perspective.org" "#net-perspective.*" :public))
                        (dsl/ctx "net-perspective.announcements"
                                 (dsl/->> "uri:feed:https://net-perspective.org/feed.atom" "#net-perspective.announcements" :public))
                        (dsl/ctx "net-perspective.*"
                                 (dsl/->> "email:admin@net-perspective.org" "#net-perspective.*" :public)))]

              b-config-options-edn
              (assoc-in a-config-options-edn
                        [:publish-identities :main-identity]
                        {:name "Bob"
                         :email "bob@example.com"
                         :ssh-key-id/public-key-path (:public b-key-pair)
                         :ssh-key-id/private-key-path (:private b-key-pair)})
                        
              b-relations-edn 
              [(dsl/ctx "#" 
                        (dsl/ctx "a-private"
                                 (dsl/->> :</contacts.alice "#private"))
                        (dsl/ctx "contacts"
                                 (dsl/ctx "alice"
                                          (dsl/->> a-ident "#self")))

                        (dsl/ctx "net-perspective.*"
                                 (dsl/-> :</contacts.alice "#net-perspective.*" :public)))]

              c-config-options-edn
              (assoc-in a-config-options-edn
                        [:publish-identities :main-identity]
                        {:name "Charlie"
                         :email "charlie@example.com"
                         :ssh-key-id/public-key-path (:public c-key-pair)
                         :ssh-key-id/private-key-path (:private c-key-pair)})
                        
              c-relations-edn 
              [(dsl/ctx "#" 
                        (dsl/ctx "contacts"
                                 (dsl/ctx "alice"
                                          (dsl/->> a-ident "#self")))

                        (dsl/ctx "net-perspective.*"
                                 (dsl/->> :</contacts.alice "#net-perspective.*" :public)))]]

          (dsl/write-contexts (str a-base-dir "/relations.edn") a-relations-edn)
          (dsl/write-config (str a-base-dir "/config.edn") a-config-options-edn)

          (dsl/write-contexts (str b-base-dir "/relations.edn") b-relations-edn)
          (dsl/write-config (str b-base-dir "/config.edn") b-config-options-edn)

          (dsl/write-contexts (str c-base-dir "/relations.edn") c-relations-edn)
          (dsl/write-config (str c-base-dir "/config.edn") c-config-options-edn)

          (sut/-main "publish" "--base-dir" a-base-dir)
          (sut/-main "fetch" "--base-dir" b-base-dir)
          (sut/-main "fetch" "--base-dir" c-base-dir)
          (with-out-str
            (is (= {["private"]
                    #{["uri:http://some-private.example.com/" ["null"]]}
                    
                    ["net-perspective"]
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]
                      ["uri:https://net-perspective.org" ["net-perspective" "*"]]}

                    ["net-perspective" "*"]
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]}

                    ["net-perspective" "announcements"]
                    #{["uri:feed:https://net-perspective.org/feed.atom" ["net-perspective" "announcements"]]}}
                   (sut/-main "build" "edn" "#**" "--base-dir" a-base-dir)))
            (is (= #{"http://some-private.example.com/" 
                     "https://net-perspective.org"
                     "feed:https://net-perspective.org/feed.atom"}
                   (sut/-main "build" "flat-uris" "#**" "--base-dir" a-base-dir))) 
            (is (= #{}
                   (sut/-main "build" "flat-emails" "#non-existent" "--base-dir" a-base-dir))) 
            (is (= #{"admin@net-perspective.org"}
                   (sut/-main "build" "flat-emails" "#net-perspective" "--base-dir" a-base-dir))) 
            (is (= {["contacts" "alice"]
                    #{[a-ident ["self"]]}

                    ["a-private"]
                    #{[:</contacts.alice ["private"]]
                      [a-ident ["private"]]}
                    
                    ["net-perspective" "*"]
                    #{[:</contacts.alice ["net-perspective" "*"]]
                      [a-ident ["net-perspective" "*"]]}

                    ["net-perspective" "announcements"]
                    #{[a-ident ["net-perspective" "announcements"]]}}
                   (sut/-main "build" "edn" "#**" "--base-dir" b-base-dir)))
            (is (= {["contacts" "alice"]
                    #{[a-ident ["self"]]}
                    
                    ["net-perspective" "*"] 
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]
                      [:</contacts.alice ["net-perspective" "*"]]
                      [a-ident ["net-perspective" "*"]]}

                    ["net-perspective" "announcements"]
                    #{[a-ident ["net-perspective" "announcements"]]
                      ["uri:feed:https://net-perspective.org/feed.atom" ["net-perspective" "announcements"]]}}
                   (sut/-main "build" "edn" "#**" "--base-dir" c-base-dir))))))))
            ;; TODO: Search the entire srv-dir for the private url 

  (testing "command failure"
    (utils/with-temp-key-pairs [a-key-pair {:no-delete *no-delete-test-data*}]
      (utils/with-temp-dir [a-base-dir {:no-delete *no-delete-test-data*}
                            srv-dir {:no-delete *no-delete-test-data*}]
        (when *no-delete-test-data*
          (println 'a-key-pair (str a-key-pair))
          (println 'a-base-dir (str a-base-dir))
          (println 'srv-dir (str srv-dir)))
        (sut/-main "init" "--base-dir" a-base-dir)
        (let [a-config-options-edn
              {:sources
               {:main 
                {:source/fn 'prspct.message-transfer/shell-source
                 :source/args
                 ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}

                :bad
                {:source/fn 'prspct.message-transfer/shell-source
                 :source/args
                 ["false" "find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

               :publishers
               {:main-publisher
                {:publisher/fn 
                 'prspct.message-transfer/shell-publisher
                 :publisher/args 
                 ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" (str srv-dir) ";"]}}

               :publish-identities 
               {:main-identity
                {:name "Alice"
                 :email "alice@example.com"
                 :ssh-key-id/public-key-path (:public a-key-pair)
                 :ssh-key-id/private-key-path (:private a-key-pair)}}

               :publish-configs 
               {:main-publish-config
                {:identity :main-identity
                 :publisher :main-publisher}}

               :default-publish-configs [:main-publish-config]}

              a-relations-edn 
              [(dsl/ctx "#" 

                        (dsl/ctx "net-perspective" {}
                                 (dsl/-> "uri:net-perspective.org" "#net-perspective.*" :public)
                                 (dsl/-> "email:admin@net-perspective.org" "#net-perspective.*" :public))
                        (dsl/ctx "net-perspective.announcements" {}
                                 (dsl/->> "uri:feed:https://net-perspective.org/feed.atom" "#net-perspective.announcements" :public))
                        (dsl/ctx "net-perspective.*" {}
                                 (dsl/->> "email:admin@net-perspective.org" "#net-perspective.*" :public)))]]
          (dsl/write-contexts (str a-base-dir "/relations.edn") a-relations-edn)
          (dsl/write-config (str a-base-dir "/config.edn") a-config-options-edn)
          (let [out-map
                (prspct.test-utils/with-out-data-map
                  (sut/-main "fetch" "--base-dir" a-base-dir))]
            (is (= :error-exit
                   (:res out-map)))
            (is (re-find #":cognitect\.anomalies/unavailable"
                         (:err out-map)))
            (is (= "" 
                   (:out out-map))))))))) 

(comment
  (binding [*no-delete-test-data* true]
    (integration-test)))
