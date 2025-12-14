(ns prspct.test-utils
  (:require
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]

   [malli.instrument :as mi]

   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [babashka.fs :as fs]
   [edamame.core :as edamame]

   [prspct.dsl :as dsl]
   [prspct.schemas :as ps]
   [prspct.cli :as cli]))

(tel/set-min-level! :warn)

(def ^:dynamic *preserve-test-data* false)
(def ^:dynamic *main* #'cli/-main)

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
     (binding [*out* s-out#
               *err* s-err#]
       (let [r# (do ~@body)]
         {:res r#
          :out (str s-out#)
          :err (str s-err#)}))))

(defn make-perspects
  ([holding-dir username-strs->relations]
   (make-perspects holding-dir username-strs->relations *ns*))
  ([holding-dir username-strs->relations kw-ns]
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
           (dsl/write-config config-path
                             (-> initial-config
                     ;; WIPTODO: Test for no publisher or incorrect config, well should probably be schema validation
                                 (assoc :sources
                                        {:main-source {:shell/args ["scp" "-r" (str srv-dir "/.") :output-dir]}}
                                        :publishers
                                        {:main-publisher {:shell/args ["scp" "-r" :input-dir-slash-dot srv-dir]}}
                                        :publish-configs
                                        {:main-publish-config {:publisher :main-publisher
                                                               :identity :main-identity}}

                                        :default-publish-configs [:main-publish-config]))))))
     (let [username-str->ident
           (into {}
                 (map (fn [username]
                        [username
                         (-> (str holding-dir "/" username "/.prspct/keys/id_prspct.pub")
                             slurp
                             ps/ssh-public-key->identifier-ssh)]))
                 username-strs)

           username-kw->ident
           (update-keys username-str->ident #(keyword (str kw-ns) %))

           username-str->config
           (into {}
                 (map (fn [username]
                        (let [base-dir (str holding-dir "/" username)
                              config-path (str base-dir "/config.edn")
                              relations-path (str base-dir "/relations.edn")]
                          [username
                           ;; WIPTODO: Can just have the base-dir be output and everything else can use that...
                           {:base-dir
                            base-dir

                            :ident
                            (get username-str->ident username)

                            :swap-relations!
                            (fn [relations]
                              (dsl/write-contexts
                               relations-path
                               (walk/prewalk-replace username-kw->ident relations)))

                            :update-config!
                            (fn [f]
                              (dsl/write-config
                               config-path
                               (-> config-path slurp edamame/parse-string f)))

                            :main
                            (fn [& args]
                              (apply *main* "--base-dir" base-dir args))}])))
                 username-strs)]
       (doseq [[username config] username-str->config]
         ((:swap-relations! config) (get username-strs->relations username)))
       username-str->config))))

(defmacro with-perspects
  {:clj-kondo/lint-as 'clojure.core/let}
  [bindings & body]
  (have! vector? bindings)
  (have! even? (count bindings))
  (let [username-str->relations
        (into {}
              (map (fn [[k v]]
                     [(str k)
                      (walk/prewalk-replace {'ctx  dsl/ctx
                                             '->   dsl/->
                                             '->>  dsl/->>}
                                            v)]))
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
         (println "perspects holding dir:" (str holding-dir#)))
       (let [username-str->relations#
             ~username-str->relations

             ~username-str->config-sym
             (make-perspects holding-dir# username-str->relations# ~(str *ns*))]
         (let ~further-bindings ~@body)))))


