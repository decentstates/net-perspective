(ns prsp.cli-test
  (:require
   [clojure.java.io]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.generators :as gen]
   [com.gfredericks.test.chuck.generators :as gen']
   [com.gfredericks.test.chuck.clojure-test :refer [checking]]


   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [babashka.fs :as fs]
   [edamame.core :as edamame]

   [prsp.dsl :as dsl]
   [prsp.schemas :as ps]
   [prsp.test-utils :refer [with-perspects *preserve-test-data* *main*]]
   [prsp.lib.utils :as utils]
   [prsp.cli :as sut]))


(prsp.test-utils/deftest-ns-schemas-test)

(deftest init-test
  (testing "basics — no args (uses defaults)"
    (utils/with-temp-dir [base-dir {}]
      (*main* "init" "--base-dir" base-dir)
      (is (= (sort (vec (map str (file-seq (fs/file base-dir)))))
             (sort [(str base-dir "")
                    (str base-dir "/relations.edn")
                    (str base-dir "/config.edn")
                    (str base-dir "/keys")
                    (str base-dir "/keys/id_prsp")
                    (str base-dir "/keys/id_prsp.pub")
                    (str base-dir "/var")
                    (str base-dir "/var/fetches")
                    (str base-dir "/var/fetches/0")
                    (str base-dir "/var/fetches/0/fetch-info.edn")
                    (str base-dir "/var/.gitignore")
                    (str base-dir "/var/fetches.HEAD")
                    (str base-dir "/var/fetches.HEAD/fetch-info.edn")])))
      (let [config (-> (str base-dir "/config.edn")
                       slurp
                       edamame/parse-string)]
        (is (= "Anonymous"
               (get-in config [:publish-identities :main-identity :name])))
        (is (re-find #"@guerrillamail\.com"
                     (get-in config [:publish-identities :main-identity :email]))))))

  (testing "explicit args override defaults"
    (utils/with-temp-dir [base-dir {}]
      (sut/-main "init" "--base-dir" base-dir
                 "--init-name" "Alice"
                 "--init-email" "alice@example.com"
                 "--no-init-generate-keys")
      (let [config (-> (str base-dir "/config.edn")
                       slurp
                       edamame/parse-string)]
        (is (= "Alice"
               (get-in config [:publish-identities :main-identity :name])))
        (is (= "alice@example.com"
               (get-in config [:publish-identities :main-identity :email])))))))


(deftest integration-basic-roundtrip-test
    (with-perspects [a
                     [(dsl/ctx "#"
                               (dsl/ctx "misc"
                                        (dsl/-> "https://wikipedia.com/" :public))
                               (dsl/ctx "private"
                                        (dsl/->> "http://some-private.example.com/")
                                        (dsl/->> "http://some-other-private.example.com/" "#private"))
                               (dsl/ctx "net-perspective"
                                        (dsl/-> "https://net-perspective.org" "#net-perspective.*" :public)
                                        (dsl/-> "email:admin@net-perspective.org" "#net-perspective.*" :public))
                               (dsl/ctx "net-perspective.announcements"
                                        (dsl/->> "feed:https://net-perspective.org/feed.atom" :public))
                               (dsl/ctx "net-perspective.*"
                                        (dsl/->> "email:admin@net-perspective.org" "#net-perspective.*" :public)))]

                     b
                     [(dsl/ctx "#"
                               (dsl/ctx "a-private"
                                        (dsl/->> :</contacts.alice "#private"))
                               (dsl/ctx "contacts"
                                        (dsl/ctx "alice"
                                                 (dsl/->> ::a "#self")))
                               (dsl/ctx "net-perspective.*"
                                        (dsl/-> :</contacts.alice "#net-perspective.*" :public)))]

                     c
                     [(dsl/ctx "#"
                               (dsl/ctx "contacts"
                                        (dsl/ctx "alice"
                                                 (dsl/->> ::a "#self")))
                               (dsl/ctx "net-perspective.*"
                                        (dsl/->> :</contacts.alice "#net-perspective.*" :public)))]]
      ((:main a) "publish")
      ((:main b) "fetch")
      ((:main c) "fetch")
      (with-out-str
        (is (= {["misc"]
                #{["https://wikipedia.com/" []]}

                ["private"]
                #{["http://some-private.example.com/" []]
                  ["http://some-other-private.example.com/" ["private"]]}

                ["net-perspective"]
                #{["email:admin@net-perspective.org" ["net-perspective" "*"]]
                  ["https://net-perspective.org" ["net-perspective" "*"]]}

                ["net-perspective" "*"]
                #{["email:admin@net-perspective.org" ["net-perspective" "*"]]}

                ["net-perspective" "announcements"]
                #{["feed:https://net-perspective.org/feed.atom" []]}}
               ((:main a) "build" "edn" "#**")))
        (is (= #{"email:admin@net-perspective.org"
                 "http://some-private.example.com/"
                 "http://some-other-private.example.com/"
                 "https://net-perspective.org"
                 "feed:https://net-perspective.org/feed.atom"
                 "https://wikipedia.com/"}
               ((:main a) "build" "flat-uris" "#**")))
        (is (= #{}
               ((:main a) "build" "flat-emails" "#non-existent")))
        (is (= #{"admin@net-perspective.org"}
               ((:main a) "build" "flat-emails" "#net-perspective")))
        (is (= {["contacts" "alice"]
                #{[(:ident a) ["self"]]}

                ["a-private"]
                #{[:</contacts.alice ["private"]]
                  [(:ident a) ["private"]]}

                ["net-perspective" "*"]
                #{[:</contacts.alice ["net-perspective" "*"]]
                  [(:ident a) ["net-perspective" "*"]]}

                ["net-perspective" "announcements"]
                #{[(:ident a) ["net-perspective" "announcements"]]}}
               ((:main b) "build" "edn" "#**")))
        (is (= {["contacts" "alice"]
                #{[(:ident a) ["self"]]}

                ["net-perspective" "*"]
                #{["email:admin@net-perspective.org" ["net-perspective" "*"]]
                  [:</contacts.alice ["net-perspective" "*"]]
                  [(:ident a) ["net-perspective" "*"]]}

                ["net-perspective" "announcements"]
                #{[(:ident a) ["net-perspective" "announcements"]]
                  ["feed:https://net-perspective.org/feed.atom" []]}}
               ((:main c) "build" "edn" "#**"))))))
            ;; TODO: Search the entire srv-dir for the private url

(deftest integration-multi-step-test
    (with-perspects [a
                     [(dsl/ctx "#misc-a"
                               (dsl/-> "https://wikipedia.com/" :public))]

                     b
                     [(dsl/ctx "#contacts.a"
                               (dsl/->> ::a "#self"))
                      (dsl/ctx "#misc-b"
                               (dsl/->> :</contacts.a "#misc-a" :public))]

                     c
                     [(dsl/ctx "#contacts.b"
                               (dsl/->> ::b "#self"))
                      (dsl/ctx "#misc-c"
                               (dsl/->> :</contacts.b "#misc-b" :public))]

                     d
                     [(dsl/ctx "#contacts.c"
                               (dsl/->> ::c "#self"))
                      (dsl/ctx "#misc-d"
                               (dsl/->> :</contacts.c "#misc-c" :public))]]
      ((:main a) "publish")
      ((:main b) "publish")
      ((:main c) "publish")
      (with-out-str
        (is (= {["misc-d"]
                #{[(:ident c) ["misc-c"]]
                  [:</contacts.c ["misc-c"]]}
                ["contacts" "c"]
                #{[(:ident c) ["self"]]}}
               ((:main d) "build" "edn" "#**"))))
      ((:main d) "fetch")
      (with-out-str
        (is (= {["misc-d"]
                #{["https://wikipedia.com/" []]
                  [(:ident a) ["misc-a"]]
                  [(:ident b) ["misc-b"]]
                  [(:ident c) ["misc-c"]]
                  [:</contacts.c ["misc-c"]]}
                ["contacts" "c"]
                #{[(:ident c) ["self"]]}}
               ((:main d) "build" "edn" "#**"))))))

(deftest integration-command-failure-test
    (with-perspects [a
                     [(dsl/ctx "#"

                               (dsl/ctx "net-perspective" {}
                                        (dsl/-> "https://net-perspective.org" "#net-perspective.*" :public)
                                        (dsl/-> "email:admin@net-perspective.org" "#net-perspective.*" :public))
                               (dsl/ctx "net-perspective.announcements" {}
                                        (dsl/->> "feed:https://net-perspective.org/feed.atom" "#net-perspective.announcements" :public))
                               (dsl/ctx "net-perspective.*" {}
                                        (dsl/->> "email:admin@net-perspective.org" "#net-perspective.*" :public)))]]
      ((:update-config! a)
       (fn [config] (assoc-in config
                              [:sources :bad]
                              {:shell/args
                               ["false" :output-dir ";"]})))
      (let [out-map
            (prsp.test-utils/with-out-data-map
              ((:main a) "fetch"))]
        (is (= :error-exit
               (:res out-map)))
        (is (re-find #":cognitect\.anomalies/unavailable"
                     (:err out-map)))
        (is (= ""
               (:out out-map))))))

(deftest build-on-uninitialised-dir-test
  (testing "build on a directory with no relations.edn exits non-zero"
    (utils/with-temp-dir [base-dir {}]
      (let [out-map (prsp.test-utils/with-out-data-map
                      (*main* "--base-dir" base-dir "build" "edn" "#**"))]
        (is (= :error-exit (:res out-map)))))))

(comment
  (binding [*preserve-test-data* true]
    (integration-multi-step-test)))
