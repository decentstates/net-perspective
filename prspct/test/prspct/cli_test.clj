(ns prspct.cli-test
  (:require 
    [clojure.pprint :refer [pprint]]
    [clojure.java.io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.test.check.generators :as gen]
    [clojure.walk :as walk]
    [com.gfredericks.test.chuck.generators :as gen']
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]

    [malli.core :as m]
    [malli.error :as me]
    [malli.generator :as mg]
    [malli.instrument :as mi]

    [taoensso.truss :refer [have have! have!? have? ex-info!]]

    [babashka.fs :as fs]
    [edamame.core :as edamame]

    [prspct.dsl :as dsl]
    [prspct.schemas :as ps]
    [prspct.test-utils]
    [prspct.lib.utils :as utils]
    [prspct.cli :as sut]))


(def ^:dynamic *preserve-test-data* false)
(def ^:dynamic *-main* #'sut/-main)


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


(defn make-perspects [holding-dir username-strs->relations]
    (let [srv-dir (str holding-dir "/srv-dir")
          username-strs (keys username-strs->relations)]
      (fs/create-dir srv-dir)
      (doseq [username username-strs]
        (let [base-dir (str holding-dir "/" username)]
          (fs/create-dir base-dir)
          (*main* "--base-dir" base-dir "init"
                  "--init-generate-keys" 
                  "--init-name" username 
                  "--init-email" (str username "@example.com"))
          (let [config-path (str base-dir "/config.edn")
                initial-config (-> config-path slurp edamame/parse-string)]
            (spit config-path
                  (-> initial-config
                      (assoc :sources 
                            {:main-source {:shell/args ["scp" "-r" (str srv-dir "/.") :output-dir]}})
                      (assoc :publishers 
                            {:main-publisher {:shell/args ["scp" "-r" :input-dir-slash-dot srv-dir]}}))))))
      (let [username-str->ident
            (into {}
                  (map (fn [username]
                         [username
                          (-> (str holding-dir "/" username "/.prspct/keys/id_prspct.pub")
                              slurp
                              ps/ssh-public-key->identifier-ssh)]))
                  username-strs)

            username-kw->ident
            (update-keys username-str->ident #(keyword (str *ns*) %))]
        (let [username-str->config
              (into {}
                    (map (fn [username]
                           (let [base-dir (str holding-dir "/" username)
                                 relations-path (str base-dir "/relations.edn")]
                             [username
                              {:base-dir 
                               base-dir

                               :swap-relations! 
                               (fn [relations]
                                 (dsl/write-contexts relations-path 
                                                     (walk/prewalk-replace username-kw->ident relations)))
                               :main 
                               (fn [& args] 
                                 (apply *-main* "--base-dir" base-dir args))}])))
                    username-strs)]
          (doseq [[username config] username-str->config]
            ((:swap-relations! config) (get username-strs->relations username)))
          username-str->config))))

(defmacro with-perspects [bindings & body]
  (have! vector? bindings)
  (have! even? (count bindings))
  (let [username-str->relations 
        (into {} 
              (map (fn [[k v]] [(str k) v]))
              (partition 2 bindings))

        username-strs
        (keys username-str->relations)

        username-str->config-sym
        (gensym "username-str->config")
          

        further-bindings
        (vec (mapcat 
               (fn [username]
                 [(symbol username) `(get ~username-str->config-sym ~username)])
               username-strs))]
    `(utils/with-temp-dir [holding-dir# {:preserve *preserve-test-data*}]
       (when *preserve-test-data*
         (println "perspects holding dir:" holding-dir#))
       (let [username-str->relations# 
             ~username-str->relations

             ~username-str->config-sym
             (make-perspects holding-dir# username-str->relations#)]
         (let ~further-bindings ~@body)))))
       

(deftest integration-test
  (testing "basic roundtrip"
    (utils/with-temp-key-pairs [a-key-pair {:preserve *preserve-test-data*}]
      (utils/with-temp-dir [a-base-dir {:preserve *preserve-test-data*}
                            b-base-dir {:preserve *preserve-test-data*}
                            c-base-dir {:preserve *preserve-test-data*}
                            srv-dir {:preserve *preserve-test-data*}] 
        (when *preserve-test-data*
          (println 'a-key-pair (str a-key-pair))
          (println 'a-base-dir (str a-base-dir))
          (println 'b-base-dir (str b-base-dir))
          (println 'c-base-dir (str c-base-dir))
          (println 'srv-dir (str srv-dir)))
        (sut/-main "init" "--base-dir" a-base-dir)
        (sut/-main "init" "--base-dir" b-base-dir "--init-generate-keys" "--init-name" "Bob" "--init-email" "bob@example.com")
        (sut/-main "init" "--base-dir" c-base-dir "--init-generate-keys" "--init-name" "Charlie" "--init-email" "charlie@example.com")

        (let [c-config-options-edn
              (edamame/parse-string (slurp (str c-base-dir "/config.edn")))
              main-identity
              (get-in c-config-options-edn [:publish-identities :main-identity])]
          (is (= "Charlie" (:name main-identity)))
          (is (= "charlie@example.com" (:email main-identity))))

        (let [a-ident 
              (-> a-key-pair :public slurp ps/ssh-public-key->identifier-ssh)

              a-config-options-edn
              {:sources
               {:main-source 
                {:shell/args
                 ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

               :publishers
               {:main-publisher
                {:shell/args
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
                        (dsl/ctx "misc"
                                 (dsl/-> "uri:https://wikipedia.com/" :public))
                        (dsl/ctx "private"
                                 (dsl/->> "uri:http://some-private.example.com/")
                                 (dsl/->> "uri:http://some-other-private.example.com/" "#private"))
                        (dsl/ctx "net-perspective"
                                 (dsl/-> "uri:https://net-perspective.org" "#net-perspective.*" :public)
                                 (dsl/-> "email:admin@net-perspective.org" "#net-perspective.*" :public))
                        (dsl/ctx "net-perspective.announcements"
                                 (dsl/->> "uri:feed:https://net-perspective.org/feed.atom" :public))
                        (dsl/ctx "net-perspective.*"
                                 (dsl/->> "email:admin@net-perspective.org" "#net-perspective.*" :public)))]

              b-config-options-edn
              (edamame/parse-string (slurp (str b-base-dir "/config.edn")))

              b-main-identity
              (get-in b-config-options-edn [:publish-identities :main-identity])
                        
              b-config-options-edn
              (assoc-in a-config-options-edn
                        [:publish-identities :main-identity]
                        b-main-identity)
                        
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
              (edamame/parse-string (slurp (str c-base-dir "/config.edn")))

              c-main-identity
              (get-in c-config-options-edn [:publish-identities :main-identity])
                        
              c-config-options-edn
              (assoc-in a-config-options-edn
                        [:publish-identities :main-identity]
                        c-main-identity)

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
            (is (= {
                    ["misc"]
                    #{["uri:https://wikipedia.com/" []]}
                    
                    ["private"]
                    #{["uri:http://some-private.example.com/" []]
                      ["uri:http://some-other-private.example.com/" ["private"]]}
                    
                    ["net-perspective"]
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]
                      ["uri:https://net-perspective.org" ["net-perspective" "*"]]}

                    ["net-perspective" "*"]
                    #{["email:admin@net-perspective.org" ["net-perspective" "*"]]}

                    ["net-perspective" "announcements"]
                    #{["uri:feed:https://net-perspective.org/feed.atom" []]}}
                   (sut/-main "build" "edn" "#**" "--base-dir" a-base-dir)))
            (is (= #{"http://some-private.example.com/" 
                     "http://some-other-private.example.com/" 
                     "https://net-perspective.org"
                     "feed:https://net-perspective.org/feed.atom"
                     "https://wikipedia.com/"}
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
                      ["uri:feed:https://net-perspective.org/feed.atom" []]}}
                   (sut/-main "build" "edn" "#**" "--base-dir" c-base-dir))))))))
            ;; TODO: Search the entire srv-dir for the private url 

  (testing "command failure"
    (utils/with-temp-key-pairs [a-key-pair {:preserve *preserve-test-data*}]
      (utils/with-temp-dir [a-base-dir {:preserve *preserve-test-data*}
                            srv-dir {:preserve *preserve-test-data*}]
        (when *preserve-test-data*
          (println 'a-key-pair (str a-key-pair))
          (println 'a-base-dir (str a-base-dir))
          (println 'srv-dir (str srv-dir)))
        (sut/-main "init" "--base-dir" a-base-dir)
        (let [a-config-options-edn
              {:sources
               {:main 
                {:shell/args
                 ["find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}

                :bad
                {:shell/args
                 ["false" "find" (str srv-dir) "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}

               :publishers
               {:main-publisher
                {:shell/args
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
  (binding [*preserve-test-data* true]
    (integration-test)))
