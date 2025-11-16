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
                    (str base-dir "/prspct.edn")
                    (str base-dir "/.prspct")
                    (str base-dir "/.prspct/fetches")
                    (str base-dir "/.prspct/fetches/0")
                    (str base-dir "/.prspct/fetches/0/fetch-info.edn")
                    (str base-dir "/.prspct/.gitignore")
                    (str base-dir "/.prspct/fetches.HEAD")
                    (str base-dir "/.prspct/fetches.HEAD/fetch-info.edn")]))))))

(deftest integration-test
  (testing "basic roundtrip"
    (utils/with-temp-key-pairs [a-key-pair {}
                                b-key-pair {}
                                c-key-pair {}]
      (utils/with-temp-dir [a-base-dir {}
                            b-base-dir {}
                            c-base-dir {}
                            srv-dir {}] 
        (sut/-main "init" "--base-dir" a-base-dir)
        (sut/-main "init" "--base-dir" b-base-dir)
        (sut/-main "init" "--base-dir" c-base-dir)
        (let [a-ident 
              (-> a-key-pair :public slurp ps/ssh-public-key->identifier-ssh)

              a-prspct-edn 
              [(dsl/ctx "#" {:np/sources {:main 
                                          {:source/fn 'prspct.message-transfer/shell-source
                                           :source/args
                                           ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

                             :np/publishers {:main
                                             {:publisher/fn 
                                              'prspct.message-transfer/shell-publisher
                                              :publisher/args 
                                              ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" (str srv-dir) ";"]}}
                             :np/publish-to [{:publisher :main
                                              :name "Alice"
                                              :email "alice@example.com"
                                              :ssh-key-id/public-key-path (:public a-key-pair)
                                              :ssh-key-id/private-key-path (:private a-key-pair)}]
                              :np.contacts/configs [{:ctx "#contacts" 
                                                     :under-namespace :contacts}]}

                        (dsl/ctx "net-perspective"
                                 (dsl/-> "uri:net-perspective.org" "#net-perspective.*" :public)
                                 (dsl/-> "email:admin@net-perspective.org" "#net-perspective.*" :public))
                        (dsl/ctx "net-perspective.announcements"
                                 (dsl/->> "uri:feed:https://net-perspective.org/feed.atom" "#net-perspective.announcements" :public))
                        (dsl/ctx "net-perspective.*"
                                 (dsl/->> "email:admin@net-perspective.org" "#net-perspective.*" :public)))]

              b-prspct-edn 
              [(dsl/ctx "#" {:np/sources {:main 
                                          {:source/fn 'prspct.message-transfer/shell-source
                                           :source/args
                                           ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

                             :np/publishers {:main
                                             {:publisher/fn 
                                              'prspct.message-transfer/shell-publisher
                                              :publisher/args 
                                              ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" (str srv-dir) ";"]}}
                             :np/publish-to [{:publisher :main
                                              :name "Bob"
                                              :email "bob@example.com"
                                              :ssh-key-id/public-key-path (:public b-key-pair)
                                              :ssh-key-id/private-key-path (:private b-key-pair)}]
                              :np.contacts/configs [{:ctx "#contacts" 
                                                     :under-namespace :contacts}]}
                        (dsl/ctx "contacts"
                                 (dsl/ctx "alice"
                                          (dsl/->> a-ident "#self")))

                        (dsl/ctx "net-perspective.*"
                                 (dsl/-> :contacts/alice "#net-perspective.*" :public)))]

              c-prspct-edn 
              [(dsl/ctx "#" {:np/sources {:main 
                                          {:source/fn 'prspct.message-transfer/shell-source
                                           :source/args
                                           ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

                             :np/publishers {:main
                                             {:publisher/fn 
                                              'prspct.message-transfer/shell-publisher
                                              :publisher/args 
                                              ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" (str srv-dir) ";"]}}
                             :np/publish-to [{:publisher :main
                                              :name "Charlie"
                                              :email "charlie@example.com"
                                              :ssh-key-id/public-key-path (:public c-key-pair)
                                              :ssh-key-id/private-key-path (:private c-key-pair)}]
                              :np.contacts/configs [{:ctx "#contacts" 
                                                     :under-namespace :contacts}]}

                        (dsl/ctx "contacts"
                                 (dsl/ctx "alice"
                                          (dsl/->> a-ident "#self")))

                        (dsl/ctx "net-perspective.*"
                                 (dsl/->> :contacts/alice "#net-perspective.*" :public)))]]
          (dsl/write-config (str a-base-dir "/prspct.edn") a-prspct-edn)
          (dsl/write-config (str b-base-dir "/prspct.edn") b-prspct-edn)
          (dsl/write-config (str c-base-dir "/prspct.edn") c-prspct-edn)
          (sut/-main "publish" "--base-dir" a-base-dir)
          (sut/-main "fetch" "--base-dir" b-base-dir)
          (sut/-main "fetch" "--base-dir" c-base-dir)
          (with-out-str
            (is (= (sut/-main "build" "edn" "#**" "--base-dir" a-base-dir)
                   {["net-perspective"]
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]
                      ["uri:net-perspective.org" ["net-perspective" "*"]]}

                    ["net-perspective" "*"]
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]}

                    ["net-perspective" "announcements"]
                    #{["uri:feed:https://net-perspective.org/feed.atom" ["net-perspective" "announcements"]]}}))
            (is (= (sut/-main "build" "flat-uris" "#**" "--base-dir" a-base-dir)
                   #{"net-perspective.org"
                     "feed:https://net-perspective.org/feed.atom"})) 
            (is (= (sut/-main "build" "flat-emails" "#non-existent" "--base-dir" a-base-dir)
                   #{})) 
            (is (= (sut/-main "build" "flat-emails" "#net-perspective" "--base-dir" a-base-dir)
                   #{"admin@net-perspective.org"})) 
            (is (= (sut/-main "build" "edn" "#**" "--base-dir" b-base-dir)
                   {["contacts" "alice"]
                    #{[a-ident ["self"]]}
                    
                    ["net-perspective" "*"]
                    #{[:contacts/alice ["net-perspective" "*"]]
                      [a-ident ["net-perspective" "*"]]}

                    ["net-perspective" "announcements"]
                    #{[a-ident ["net-perspective" "announcements"]]}}))
            (is (= (sut/-main "build" "edn" "#**" "--base-dir" c-base-dir)
                   {
                    ["contacts" "alice"]
                    #{[a-ident ["self"]]}
                    
                    ["net-perspective" "*"] 
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]
                      [:contacts/alice ["net-perspective" "*"]]
                      [a-ident ["net-perspective" "*"]]}

                    ["net-perspective" "announcements"]
                    #{[a-ident ["net-perspective" "announcements"]]
                      ["uri:feed:https://net-perspective.org/feed.atom" ["net-perspective" "announcements"]]}})))))))

  (testing "command failure"
    (utils/with-temp-key-pairs [a-key-pair {}]
      (utils/with-temp-dir [a-base-dir {}
                            srv-dir {}]
        (sut/-main "init" "--base-dir" a-base-dir)
        (let [a-prspct-edn 
              [(dsl/ctx "#" {:np/sources {:main 
                                          {:source/fn 'prspct.message-transfer/shell-source
                                           :source/args
                                           ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}

                                          :bad
                                          {:source/fn 'prspct.message-transfer/shell-source
                                           :source/args
                                           ["false" "find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

                             :np/publishers {:main
                                             {:publisher/fn 
                                              'prspct.message-transfer/shell-publisher
                                              :publisher/args 
                                              ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" (str srv-dir) ";"]}}
                             :np/publish-to [{:publisher :main
                                              :name "Alice"
                                              :email "alice@example.com"
                                              :ssh-key-id/public-key-path (:public a-key-pair)
                                              :ssh-key-id/private-key-path (:private a-key-pair)}]}

                        (dsl/ctx "net-perspective" {}
                                 (dsl/-> "uri:net-perspective.org" "#net-perspective.*" :public)
                                 (dsl/-> "email:admin@net-perspective.org" "#net-perspective.*" :public))
                        (dsl/ctx "net-perspective.announcements" {}
                                 (dsl/->> "uri:feed:https://net-perspective.org/feed.atom" "#net-perspective.announcements" :public))
                        (dsl/ctx "net-perspective.*" {}
                                 (dsl/->> "email:admin@net-perspective.org" "#net-perspective.*" :public)))]]
          (dsl/write-config (str a-base-dir "/prspct.edn") a-prspct-edn)
          (let [out-map
                (prspct.test-utils/with-out-data-map
                  (sut/-main "fetch" "--base-dir" a-base-dir))]
            (is (= (:res out-map)
                   :error-exit))
            (is (re-find #":cognitect\.anomalies/unavailable"
                         (:err out-map)))
            (is (= (:out out-map) ""))))))))

(comment
  (integration-test))
