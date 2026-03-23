(ns prsp.cli
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.set :as set]
   [clojure.stacktrace]
   [clojure.walk :as walk]

   [cognitect.anomalies :as anom]

   [taoensso.telemere :as tel]
   [taoensso.telemere.utils]
   [taoensso.truss :as truss :refer [have have! have!? have? ex-info! try*]]

   [malli.core :as m]
   [malli.util :as mu]
   [malli.transform :as mt]

   [edamame.core :as edamame]

   [babashka.cli :as cli]
   [babashka.fs :as fs]

   [prsp.lib.utils :as utils]
   [prsp.message-transfer :as message-transfer]
   [prsp.resolution :as resolution]
   [prsp.schemas :as ps]
   [prsp.user-commands :as user-commands])
  (:gen-class))

(def common-cli-spec
  {:spec
   (sorted-map
    :base-dir
    {:desc "Relative to the cwd."
     :default "./"
     :coerce :string
     ::relative-to :cwd}

    :prsp-dir
    {:default "./.prsp"
     :coerce :string
     :desc "Relative to the base dir."
     ::relative-to :base-dir}

    :user-relations-path
    {:default "./relations.edn"
     :coerce :string
     :desc "Relative to the base dir."
     ::relative-to :base-dir}

    :user-config-options-path
    {:default "./config.edn"
     :coerce :string
     :desc "Relative to the base dir."
     ::relative-to :base-dir}

    :output-dir
    {:default "./output"
     :coerce :string
     :desc "Relative to the base dir."
     ::relative-to :base-dir}

    :fetches-dir
    {:default "./fetches"
     :coerce :string
     :desc "Relative to the prsp dir."
     ::relative-to :prsp-dir}

    :fetch-head-symlink-path
    {:default "./fetches.HEAD"
     :coerce :string
     :desc "Relative to the prsp dir."
     ::relative-to :prsp-dir}

    :last-publish-info-path
    {:default "./last-publish-info.edn"
     :coerce :string
     :desc "Relative to the prsp dir."
     ::relative-to :prsp-dir}

    :log-level
    {:desc "trace, debug, info, warn, error, fatal"
     :coerce :keyword
     :default :warn}

    :print-build-info
    {:desc "Print build-info.edn instead of running command."
     :default false
     :coerce :boolean}

    :print-cli-options
    {:desc "Print cli options instead of running command."
     :default false
     :coerce :boolean}

    :print-cli-context
    {:desc "Print cli context instead of running command."
     :default false
     :coerce :boolean}

    :version
    {:desc "Print version instead of running command."
     :default false
     :coerce :boolean}

    :help
    {:alias :h
     :desc "Display this help."
     :default false
     :coerce :boolean})})

(def non-common-cli-spec
  {:spec
   (sorted-map
    :build-idents
    {:alias :i
     :desc "User to build. Can use a :</context.include or provide an ident."
     :collect (fn [coll arg-value]
                (have! string? arg-value)
                (conj (or coll #{})
                      (if (str/starts-with? arg-value ":")
                        (keyword (subs arg-value 1))
                        arg-value)))
     :default #{:self}
     :required true}

    :build-target
    {:desc (->> (methods user-commands/build!) keys (map name) sort (str/join ", "))
     :coerce :keyword
     :default :edn
     :required true}

    :build-context
    {:desc "The context to use to build, accepts context matchers, e.g.: #** #a.* #b.**"
     :coerce :string
     :default "#**"
     :required true}

    :publications-output-dir
    {:desc "Path to output publications to."
     :coerce :string
     :default "./publications/"
     :required true
     ::relative-to :cwd}

    :init-generate-keys
    {:desc "Generate a key-pair during init."
     :coerce :boolean
     :default true}

    :init-generate-keys-dir
    {:desc "If using --init-generate-keys, the dir to generate the keys in. Will create the dir if needed. Relative to prsp dir."
     :coerce :string
     :default "./keys"
     ::relative-to :prsp-dir}

    :init-generate-keys-name
    {:desc "If using --init-generate-keys, the name for the key."
     :coerce :string
     :default "id_prsp"}

    :init-name
    {:desc "Name to use in your config.edn."
     :coerce :string
     :default "Anonymous"}

    :init-email
    {:desc "Email to use in your config.edn."
     :coerce :string
     :required false})})


;; Option keys must be unique:
(have! empty? (set/intersection (set (keys (:spec common-cli-spec)))
                                (set (keys (:spec non-common-cli-spec)))))

(declare dispatch-table)

(defn middleware-normalize-paths [handler]
  (fn [ctx]
    (let [opts
          (:opts ctx)

          specs (merge (:spec common-cli-spec)
                       (:spec non-common-cli-spec))

          resolve-opt-pair
          (fn [opts [k v]]
            (let [spec (get specs k)
                  relative-to (::relative-to spec)
                  relative? (and relative-to (fs/relative? v))
                  v' (if relative?
                       (str (fs/normalize (fs/path (get opts relative-to) v)))
                       v)]
              [k v']))

          relative-to?
          (fn [[k _v] relative-to]
            (let [spec (get specs k)]
              (= (::relative-to spec)
                 relative-to)))

          ;; resolve relative-to cwd
          opts
          (into
           {}
           (map (fn [[k v :as opt-pair]]
                  (if (relative-to? opt-pair :cwd)
                    [k (str (fs/canonicalize v))]
                    opt-pair)))
           opts)

          ;; resolve relative-to base-dir
          opts
          (into
           {}
           (map (fn [opt-pair]
                  (if (relative-to? opt-pair :base-dir)
                    (resolve-opt-pair opts opt-pair)
                    opt-pair)))
           opts)

          ;; resolve relative-to prsp-dir
          opts
          (into
           {}
           (map (fn [opt-pair]
                  (if (relative-to? opt-pair :prsp-dir)
                    (resolve-opt-pair opts opt-pair)
                    opt-pair)))
           opts)]

      (let [p (:fetch-head-symlink-path opts)]
        (have! (or (fs/sym-link? p)
                   (not (fs/exists? p)))))

      (handler (merge ctx
                      {:opts opts})))))

(defn middleware-exception-handling [handler]
  (fn [ctx]
    (truss/try*
     (truss/with-ctx+ ctx
       (handler ctx))
      ;; clj-kondo doesn't lint truss/try* correctly:
     #_{:clj-kondo/ignore [:unresolved-symbol]}
     (catch :all e
       (tel/error! {:level :debug} e)
       (let [exception-write-path
             (str (fs/create-temp-file
                   {:prefix "prsp-exception-"
                    :suffix ".edn"}))

             ex-d (ex-data e)]
         (spit exception-write-path
               (with-out-str
                 (pprint e)
                 (println)
                 (pprint (utils/build-info))))
         (binding [*out* *err*]
           (println "Error:" (ex-message e))
           (when-let [category (::anom/category ex-d)]
             (println "Error Category:" category))
           (when-let [instructions (:instructions ex-d)]
             (println)
             (println "Instructions:")
             (print instructions))
           (println)
           (println "Full report at:")
           (println exception-write-path)
           (println)
           (println "Bugs: mailto:~decentstates/net-perspective-alpha@lists.sr.ht"))
         :error-exit)))))

(defn middleware-eventer [handler]
  (fn [ctx]
    (tel/event! ::middleware-eventer:start)
    (let [res (handler ctx)]
      (tel/event! ::middleware-eventer:finish)
      res)))

(defn middleware-logging-level [handler]
  (fn [ctx]
    (let [log-level (get-in ctx [:opts :log-level])]
      (tel/with-min-level
        log-level
        (do
          (tel/spy! :debug log-level)
          (handler ctx))))))

(defn middleware-print-cli-options [handler]
  (fn [ctx]
    (if (get-in ctx [:opts :print-cli-options])
      (do
        (pprint ctx)
        (flush))
      (handler ctx))))

(defn middleware-print-cli-context [handler]
  (fn [ctx]
    (if (get-in ctx [:opts :print-cli-context])
      (do
        (pprint ctx)
        (flush))
      (handler ctx))))

(defn middleware-print-build-info [handler]
  (fn [ctx]
    (if (get-in ctx [:opts :print-build-info])
      (do
        (pprint (utils/build-info))
        (flush))
      (handler ctx))))

(defn middleware-version [handler]
  (fn [ctx]
    (if (get-in ctx [:opts :version])
      (do
        (println "version:" (:version (utils/build-info)))
        (println "git-hash:" (:git-hash (utils/build-info)))
        (flush))
      (handler ctx))))

(defn print-help [ctx]
  (let [dispatch (:dispatch ctx)
        help
        (with-out-str
          (if-let [dispatch-spec (and
                                  (not-empty dispatch)
                                  (some #(and (= dispatch (:cmds %)) %)
                                        dispatch-table))]
            (do
              (println (str "Usage: " (get *command-line-args* 0 "prsp") " " (::usage dispatch-spec)))
              (when (::cmd-spec dispatch-spec)
                (println)
                (println "Subcommand options:")
                (println (cli/format-opts {:spec (::cmd-spec dispatch-spec)}))))

            ;; else
            (do
              (println (str "Usage: " (get *command-line-args* 0 "prsp") " sub-command [--help]"))
              (println)
              (println "Available subcommands:")
              (println
               (cli/format-table
                {:rows
                 (into []
                       (comp
                        (filter #(not-empty (:cmds %)))
                        (map #(vector (str/join ", " (:cmds %)) (::desc %))))
                       dispatch-table)}))))
          (println)
          (println "General Options:")
          (println (cli/format-opts common-cli-spec)))]
    (print help)
    (flush)))

(defn middleware-help [handler]
  (fn [ctx]
    (if (get-in ctx [:opts :help])
      (print-help ctx)
      (handler ctx))))

;; TODO: Move this to middleware and apply to all coersion errors
(defn- handle-file-coercion-error [file-path e]
  (let [ex-d (ex-data e)]
    (if (= (:type ex-d) ::m/coercion)
      (let [errors
            (-> ex-d :data :explain :errors)

            input-remaining?
            (fn [error]
              (= (:type error)
                 ::m/input-remaining))

            has-input-remaining
            (some input-remaining? errors)

            non-input-remaining-errors
            (filterv (complement input-remaining?) errors)

            error-msgs
            (mapv
             (fn [error]
               (with-out-str
                 (let [parent-in
                       (butlast (:in error))

                       parent-errors
                       (filterv #(= (:in %) parent-in) errors)

                       some-parent-error
                       (first parent-errors)

                       pos
                       (or (meta (:value error))
                           (meta (:value some-parent-error)))

                       pos-str
                       (if pos
                         (str ":" (:row pos) ":" (:col pos)
                              "-" (:end-row pos) ":" (:end-col pos))
                         "")

                       schema
                       (:schema error)

                       stripped-schema
                       (m/walk
                        schema
                        (m/schema-walker
                         (fn [schema]
                           (mu/update-properties schema dissoc :gen/fmap :gen/schema :gen/max :gen/min
                                                 :gen/return :gen/elements :gen/gen :gen/infinite? :gen/NaN?))))]
                   (println "Validation Error:" (str file-path pos-str))
                   (println)
                   (println "Value:")
                   (pprint (:value error))
                   (println)
                   (when some-parent-error
                     (println "In:")
                     (pprint (:value some-parent-error))
                     (println))
                   (println "Malli Schema:")
                   (pprint (m/form stripped-schema))
                   (println)
                   (println "For more info see: https://github.com/metosin/malli?tab=readme-ov-file#malli"))))
             non-input-remaining-errors)

            error-msgs
            (if has-input-remaining
              (conj error-msgs "Unfinished validation due to errors.\n")
              error-msgs)

            instructions
            (str "\n" (str/join "\n-----------------\n\n" error-msgs))]

        (ex-info! (str "Validation failed: " file-path)
                  {::anom/category ::anom/incorrect
                   :instructions instructions}
                  e))
        ;; else
      (throw e))))


(defn load-user-relations [user-relations-path]
  (let [user-relations-text
        (slurp user-relations-path)

        _ (tel/event! ::load-user-relations:loaded-file)

        user-relations-edn
        (try*
         (edamame/parse-string-all user-relations-text)
         (catch :ex-info e
           (let [ex-d (ex-data e)]
             (if (= (:type ex-d)
                    :edamame/error)
               (ex-info! (str "Can't parse `" user-relations-path "`")
                         (merge ex-d
                                {::anom/category ::anom/incorrect
                                 :instructions (ex-message e)})
                         e)
               (throw e)))))

        _ (tel/event! ::load-user-relations:parsed-user-relations)

        user-relations-dsl
        (try*
         (m/coerce
          #'ps/UserRelationsDSL
          user-relations-edn
          (ps/file-path-transformer (fs/parent user-relations-path)))
         (catch :ex-info e
           (handle-file-coercion-error user-relations-path e)))

        _ (tel/event! ::load-user-relations:coerced-user-relations)]
    (ps/user-relations-dsl->user-config user-relations-dsl)))

;; TODO: Rename user-config-options to something else
(defn load-user-config-options [user-config-options-path]
  (let [user-config-options-text
        (slurp user-config-options-path)

        _ (tel/event! ::load-user-config-options:loaded-file)

        user-config-options-edn
        (try*
         (edamame/parse-string user-config-options-text)
         (catch :ex-info e
           (let [ex-d (ex-data e)]
             (if (= (:type ex-d)
                    :edamame/error)
               (ex-info! (str "Can't parse `" user-config-options-path "`")
                         (merge ex-d
                                {::anom/category ::anom/incorrect
                                 :instructions (ex-message e)})
                         e)
               (throw e)))))

        _ (tel/event! ::load-user-config-options:parsed-user-config-options)

        user-config-options-dsl
        (try*
         (m/coerce
          #'ps/UserConfigOptions
          user-config-options-edn
          (mt/transformer
           (mt/default-value-transformer {::mt/add-optional-keys true})
           (ps/file-path-transformer (fs/parent user-config-options-path))))
         (catch :ex-info e
           (handle-file-coercion-error user-config-options-path e)))

        _ (tel/event! ::load-user-config-options:coerced-user-config-options)]
    user-config-options-dsl))

(defn middleware-resolve-config [handler]
  (fn [ctx]
    (tel/event! ::middleware-resolve-config)
    (have! :now-instant ctx)
    (let [opts
          (:opts ctx)

          user-config
          {:user-contexts
           (load-user-relations (:user-relations-path opts))

           :user-config-options
           (load-user-config-options (:user-config-options-path opts))}

          ;; TODO: Move processing of messages around here
          fetched-publication-messages
          (message-transfer/load-fetch (:fetch-head-symlink-path opts))

          resolved-config
          (resolution/resolve-config user-config (:now-instant ctx) fetched-publication-messages)]
      (tel/spy! :debug resolved-config)
      (handler (merge ctx
                      {:user-config user-config
                       :resolved-config resolved-config})))))

(defn middleware-with-cwd [handler]
  (fn [ctx]
    (let [opts (:opts ctx)
          base-dir (:base-dir opts)]
      (binding [utils/*sh-cwd* base-dir]
        (handler ctx)))))

(defn middleware-now-instant [handler]
  (fn [ctx]
    (handler (assoc ctx :now-instant (java.time.Instant/now)))))

(def common-middlewares
  (comp
   middleware-logging-level
   middleware-now-instant
   middleware-eventer
   middleware-exception-handling
   middleware-help
   middleware-print-build-info
   middleware-version
   middleware-normalize-paths
   middleware-with-cwd
   middleware-print-cli-options))

(defn wrap-middlewares
  "Applied from top to bottom"
  [handler middlewares]
  ;; We want middleware-print-cli-context after all the other middleware to get maximum context
  (let [middlewares (conj middlewares middleware-print-cli-context)]
    (reduce (fn [acc middleware] (middleware acc)) handler (reverse middlewares))))

(defn init! [ctx]
  (let [opts (:opts ctx)
        opts (update opts :init-email #(or % (utils/random-guerrilla-mail)))

        {:keys [user-config-options-path
                init-generate-keys init-generate-keys-dir init-generate-keys-name
                init-name init-email]}
        opts]

    (doseq [kw [:prsp-dir :user-config-path]]
      (let [p (get opts kw)]
        (when (fs/exists? p)
          (ex-info! (str "Cannot init: path `" p "` (`" kw "`) already exists.")
                    {::anom/category ::anom/incorrect
                     :opts opts}))))

    (doseq [kw [:base-dir :prsp-dir :fetches-dir]]
      (fs/create-dirs (fs/path (get opts kw))))

    (spit (str (fs/path (:prsp-dir opts) ".gitignore"))
          "*")

    (let [config-options-init
          (edamame/parse-string (slurp (io/resource "config-options-init.edn")))

          replacements
          (as-> {} $
            (if init-generate-keys
              (let [private-key-path (str (fs/path init-generate-keys-dir init-generate-keys-name))
                    public-key-path (str (fs/path init-generate-keys-dir (str init-generate-keys-name ".pub")))
                    maybe-relativize
                    (fn [path]
                      (if (fs/starts-with? path (fs/parent user-config-options-path))
                        (str (fs/relativize (fs/parent user-config-options-path) path))
                        path))]
                (when (fs/exists? private-key-path)
                  (ex-info! (str "Cannot generate-keys: path `" private-key-path "` already exists.")
                            {::anom/category ::anom/incorrect
                             :opts opts}))
                (fs/create-dirs init-generate-keys-dir)
                (have! (utils/ssh-keygen "-t" "ed25519" "-f" private-key-path "-q" "-N" ""))
                (have! fs/exists? private-key-path)
                (have! fs/exists? public-key-path)
                (-> $
                    (assoc :fill-in-your/private-key-path (maybe-relativize private-key-path))
                    (assoc :fill-in-your/public-key-path (maybe-relativize public-key-path))))
              $)

            (if init-name
              (assoc $ :fill-in-your/name init-name)
              $)

            (if init-email
              (assoc $ :fill-in-your/email init-email)
              $))

          config-options
          (walk/postwalk-replace replacements config-options-init)]
      (spit (:user-config-options-path opts)
            (with-out-str (pprint config-options))))

    (spit (:user-relations-path opts)
          (slurp (io/resource "relations-init.edn")))

    (let [fetch-0 (fs/path (:fetches-dir opts) "0")]
      (fs/create-dirs fetch-0)
      (spit (str (fs/path fetch-0 "fetch-info.edn"))
            {})
      (fs/create-sym-link (fs/path (:fetch-head-symlink-path opts))
                          fetch-0))))

(def dispatch-table
  (mapv
   (fn [dispatch-spec]
     (assoc dispatch-spec :spec (merge (:spec common-cli-spec)
                                       (::cmd-spec dispatch-spec))))
   [{:cmds ["init"]
     ::desc "Initialise the current directory"
     ::usage "init"
     :fn (wrap-middlewares init!
                           [common-middlewares])
     ::cmd-spec (select-keys (:spec non-common-cli-spec)
                             [:init-generate-keys
                              :init-generate-keys-dir
                              :init-generate-keys-name
                              :init-name
                              :init-email])}

    {:cmds ["fetch"]
     ::desc "Fetch from sources as per your configuration."
     ::usage "fetch"
     :fn (wrap-middlewares (fn [{:keys [opts resolved-config]}]
                             (user-commands/fetch! resolved-config
                                                   (:fetch-head-symlink-path opts)
                                                   (:fetches-dir opts)))
                           [common-middlewares
                            middleware-resolve-config])}

    {:cmds ["write-publications"]
     ::desc "Write publications to files, useful for debugging."
     ::usage "write-publications output-dir"
     :fn (wrap-middlewares (fn [{:keys [opts resolved-config now-instant]}]
                             (let [{:keys [publications-output-dir]} opts]
                               (user-commands/publications! resolved-config now-instant publications-output-dir)))
                           [common-middlewares
                            middleware-resolve-config])
     ::cmd-spec (select-keys (:spec non-common-cli-spec) [:publications-output-dir])
     :args->opts [:publications-output-dir]}

    {:cmds ["publish"]
     ::desc "Publish to publishers as per your configuration."
     ::usage "publish"
     :fn (wrap-middlewares (fn [{:keys [opts resolved-config now-instant]}]
                             (let [{:keys [last-publish-info-path]} opts]
                               (user-commands/publish! resolved-config now-instant last-publish-info-path)))
                           [common-middlewares
                            middleware-resolve-config])}

    {:cmds ["build"]
     ::desc "Build a target perspective."
     ::usage "build TARGET [BUILD-CONTEXT] [OPTIONS...]"
     :fn (wrap-middlewares (fn [{:keys [opts resolved-config]}]
                             (let [{:keys [build-target build-context build-idents]} opts
                                   output (user-commands/build! build-target build-context build-idents resolved-config)
                                   serialize-fn (:serialize-fn (meta output))]
                               (print (serialize-fn output))
                               output))
                           [common-middlewares
                            middleware-resolve-config])
     ::cmd-spec
     (select-keys (:spec non-common-cli-spec)
                  [:build-target
                   :build-context
                   :build-idents])

     :validate {:build-target (->> (methods user-commands/build!) keys set)
                :build-context {:pred (m/validator #'ps/Context)}
                :ex-msg (fn [m]
                          (str "Invalid value for option :build-context: " (:value m) "\n"
                               "Should be e.g.: #**, #food.deep-fried, #plant.herbs.*"))}
     :args->opts [:build-target :build-context]}

    {:cmds []
     :fn (wrap-middlewares print-help
                           [common-middlewares])}]))

(defn -main [& args]
  (tel/event! ::-main :trace)
  (let [ret (cli/dispatch dispatch-table args)]
    (flush)
    ret))


(defn run [command-line-args]
  (apply -main (str/split command-line-args #" ")))

(comment
  (tel/with-min-level :debug
    (-main "init" "--print-cli-options"))

  (-main "build" "flat-ssh-keys" "#underties" "--base-dir" "/home/ds/perspects/ds@underties" "--log-level" "info")
  (run "fetch --base-dir /home/ds/perspects/ds@underties")
  (run "publications --base-dir /home/ds/perspects/j")
  (run "fetch --base-dir /home/ds/perspects/j")
  (run "init --base-dir /tmp/flippy0")
  (-main "--print-build-info")
  (-main))
