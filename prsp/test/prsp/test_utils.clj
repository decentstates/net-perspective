(ns prsp.test-utils
  (:require
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [clojure.pprint :refer [pprint]]
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]

   [malli.instrument :as mi]

   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [babashka.fs :as fs]
   [edamame.core :as edamame]

   [prsp.dsl :as dsl]
   [prsp.schemas :as ps]
   [prsp.cli :as cli]))

(tel/set-min-level! :warn)

(defn make-binary-main
  "Returns a function that calls the prsp binary at `binary-path` with the given args.
   Forwards stdout/stderr to the current *out*/*err* and returns a value matching
   what cli/-main would return:
   - build edn  → parsed EDN map (stdout is a pprinted map)
   - build flat-* → set of strings (stdout is newline-separated plain text)
   - init/publish/fetch → nil
   - any failure → :error-exit"
  [binary-path]
  (fn [& args]
    (let [{:keys [exit out err]} (apply shell/sh binary-path (map str args))]
      (.write *out* out)
      (.write *err* err)
      (if (not= 0 exit)
        :error-exit
        (let [trimmed    (str/trim out)
              build-cmd? (boolean (some #{"build"} args))]
          (cond
            ;; EDN collection output (build edn → pprinted map starting with "{")
            (str/starts-with? trimmed "{")
            (edamame/parse-string trimmed)

            ;; Flat build: newline-separated plain-text items → reconstruct as set
            (and build-cmd? (seq trimmed))
            (into #{} (remove empty?) (str/split-lines trimmed))

            ;; Flat build with no results (e.g. no matching emails) → empty set
            (and build-cmd? (empty? trimmed))
            #{}

            ;; Non-build commands (init, fetch, publish) → return nil
            :else nil))))))

(def ^:dynamic *preserve-test-data* false)
(def ^:dynamic *main*
  (if-let [binary (System/getenv "PRSP_BINARY")]
    (make-binary-main binary)
    #'cli/-main))

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
                         (-> (str holding-dir "/" username "/.prsp/keys/id_prsp.pub")
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


