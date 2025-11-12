(ns prspct.cli
  (:require 
    [clojure.java.io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.stacktrace]
    [clojure.walk]

    [cognitect.anomalies :as anom]

    [taoensso.telemere :as tel]
    [taoensso.telemere.utils]
    [taoensso.truss :as truss :refer [have have! have!? have? ex-info! try*]]

    [malli.core :as m]
    [malli.error :as me]
    [malli.util :as mu]

    [edamame.core :as edamame]

    [babashka.cli :as cli]
    [babashka.fs :as fs]

    [prspct.lib.utils :as utils]
    [prspct.dsl :as dsl]
    [prspct.message-transfer :as message-transfer]
    [prspct.publication :as publication]
    [prspct.relation-graph :as rel-graph]
    [prspct.resolution :as resolution]
    [prspct.schemas :as ps]
    [prspct.user-commands :as user-commands])
  (:import
    java.io.File)
  (:gen-class))

(def common-cli-spec 
  {:spec
   (sorted-map
     :base-dir 
     {:desc "Relative to the cwd."
      :default "./"
      :coerce :string}

     :prspct-dir
     {:default "./.prspct"
      :coerce :string
      :desc "Relative to the base dir."
      ::relative-to :base-dir}

     :user-config-path 
     {:default "./prspct.edn"
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
      :desc "Relative to the prspct dir."
      ::relative-to :prspct-dir}

     :fetch-head-symlink-path
     {:default "./fetches.HEAD"
      :coerce :string
      :desc "Relative to the prspct dir."
      ::relative-to :prspct-dir}

     :log-level
     {:desc "trace, debug, info, warn, error, fatal"
      :coerce :keyword
      :default :warn}

     :print-build-info
     {:desc "Print build-info.edn instead of running command."
      :default false
      :coerce :boolean}

     :print-options
     {:desc "Print options set instead of running command."
      :default false
      :coerce :boolean}

     :version
     {:desc "Print version instead of running command."
      :default false
      :coerce :boolean}

     :help
     {:desc "Display this help."
      :default false
      :coerce :boolean})})

(declare dispatch-table) 

(defn middleware-normalize-paths [handler]
  (fn [ctx]
    (let [opts
          (:opts ctx)

          ;; resolve base-dir
          opts
          (update opts :base-dir (comp str fs/canonicalize))

          resolve-opt-pair 
          (fn [opts [k v]]
            (let [spec (get-in common-cli-spec [:spec k])
                  relative-to (::relative-to spec)
                  relative? (and relative-to (fs/relative? v))
                  v' (if relative?
                       (str (fs/normalize (fs/path (get opts relative-to) v)))
                       v)]
             [k v']))

          relative-to?
          (fn [[k _v] relative-to]
            (let [spec (get-in common-cli-spec [:spec k])]
              (= (::relative-to spec)
                 relative-to)))

          ;; resolve relative-to base-dir
          opts
          (into 
            {}
            (map (fn [opt-pair]
                   (if (relative-to? opt-pair :base-dir)
                     (resolve-opt-pair opts opt-pair)
                     opt-pair)))
            opts)

          ;; resolve relative-to prspct-dir
          opts
          (into 
            {}
            (map (fn [opt-pair]
                   (if (relative-to? opt-pair :prspct-dir)
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
      (catch :all e
        (tel/error! {:level :debug} e)
        (let [exception-write-path 
              (str (fs/create-temp-file 
                     {:prefix "prspct-exception-"
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

(defn middleware-logging-level! [handler]
  (fn [ctx]
    (let [log-level (get-in ctx [:opts :log-level])]
      (tel/with-min-level 
        log-level
        (do
          (tel/spy! :debug log-level)
          (handler ctx))))))

(defn middleware-print-options [handler]
  (fn [ctx]
    (if (get-in ctx [:opts :print-options])
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

(defn print-help [dispatch]
  (let [help
        (with-out-str
          (if-let [dispatch-spec (and
                                   (not-empty dispatch)
                                   (some #(and (= dispatch (:cmds %)) %) 
                                         dispatch-table))]
            (do
              (println (str "Usage: " (get *command-line-args* 0 "prspct") " " (::usage dispatch-spec)))
              (when (::cmd-spec dispatch-spec)
                (println)
                (println "Subcommand options:")
                (println (cli/format-opts {:spec (::cmd-spec dispatch-spec)}))))

            ;; else
            (do
              (println (str "Usage: " (get *command-line-args* 0 "prspct") " sub-command [--help]"))
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
      (print-help (:dispatch ctx))
      (handler ctx))))

(defn- user-config-dsl-coercion-error [user-config-path e]
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
                      (println "Validation Error:" (str user-config-path pos-str))
                      (println)
                      (println "Value:")
                      (pprint (:value error))
                      (println)
                      (when some-parent-error
                        (println "In:")
                        (pprint (:value some-parent-error))
                        (println))
                      (println "Schema:")
                      (pprint stripped-schema))))
                non-input-remaining-errors)

              error-msgs 
              (if has-input-remaining
                (conj error-msgs "Unfinished validation due to errors.\n")
                error-msgs)

              instructions
              (str "\n" (str/join "\n-----------------\n\n" error-msgs))]

          (ex-info! (str "Validation failed for user-config: " user-config-path)
                    {::anom/category ::anom/incorrect
                     :instructions instructions}
                    e))
        ;; else
        (throw e))))

(defn load-user-config [user-config-path]
  (let [user-config-text 
        (slurp user-config-path)

        _ (tel/event! ::load-user-config:loaded-file)

        user-config-edn 
        (try*
          (edamame/parse-string user-config-text)
          (catch :ex-info e
            (let [ex-d (ex-data e)]
              (if (= (:type ex-d)
                     :edamame/error)
                (ex-info! (str "Can't parse `" user-config-path "`") 
                          (merge ex-d 
                                 {::anom/category ::anom/incorrect
                                  :instructions (ex-message e)}) 
                          e)
                (throw e)))))

        _ (tel/event! ::load-user-config:parsed-user-config)

        user-config-dsl
        (try*
          (m/coerce #'ps/UserConfigDSL user-config-edn)
          (catch :ex-info e
            (user-config-dsl-coercion-error user-config-path e)))

        _ (tel/event! ::load-user-config:coerced-user-config)]

        
    (ps/user-config-dsl->user-config user-config-dsl)))

(defn middleware-user-config [handler]
  (fn [ctx]
    (let [opts (:opts ctx)]
      (handler (merge ctx
                      {:user-config  (load-user-config (:user-config-path opts))})))))

(defn middleware-resolve-config [handler]
  (fn [ctx]
    (tel/event! ::middleware-resolve-config)
    (let [opts (:opts ctx)
          user-config (load-user-config (:user-config-path opts))
          fetched-publication-messages (message-transfer/load-fetch (:fetch-head-symlink-path opts))
          resolved-config (resolution/resolve-config user-config fetched-publication-messages)]
      (tel/spy! :debug resolved-config)
      (handler (merge ctx
                      {:user-config user-config
                       :resolved-config resolved-config})))))

(def common-middlewares
  (comp
    middleware-logging-level!
    middleware-eventer
    middleware-exception-handling
    middleware-help
    middleware-print-options
    middleware-print-build-info
    middleware-version
    middleware-normalize-paths))

(defn wrap-middlewares   
  "Applied from top to bottom"
  [handler middlewares]
  (reduce (fn [acc middleware] (middleware acc)) handler (reverse middlewares)))

(defn init! [ctx]
  (let [opts (:opts ctx)

        edn 
        [(dsl/ctx "#" {:np/sources {}
                       :np/publishers {}
                       :np/publish-to []}
                  (dsl/ctx "underties" {}))]]

    (doseq [kw [:prspct-dir :user-config-path]]
      (let [p (get opts kw)]
        (when (fs/exists? p)
          (ex-info! (str "Cannot init: path `" p "` (`" kw "`) already exists.") 
                    {::anom/category ::anom/incorrect
                     :opts opts}))))

    (doseq [kw [:base-dir :prspct-dir :fetches-dir]]
      (fs/create-dirs (fs/path (get opts kw))))

    (spit (str (fs/path (:prspct-dir opts) ".gitignore"))
          "*")

    (spit (:user-config-path opts)
          (with-out-str 
            (binding [clojure.pprint/*print-right-margin* 120] 
              (pprint edn))))

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
                            [common-middlewares])}
     {:cmds ["fetch"]
      ::desc "Fetch from sources listed in prspct.edn"
      ::usage "fetch"
      :fn (wrap-middlewares (fn [{:keys [opts resolved-config]}]
                              (user-commands/fetch! resolved-config
                                                    (:fetch-head-symlink-path opts)
                                                    (:fetches-dir opts)))
                            [common-middlewares
                             middleware-resolve-config])}
     {:cmds ["publish"]
      ::desc "Publish to publishers as per prspct.edn"
      ::usage "publish"
      :fn (wrap-middlewares (fn [{:keys [resolved-config]}]
                              (user-commands/publish! resolved-config))
                            [common-middlewares
                             middleware-resolve-config])}
     {:cmds ["build"]
      ::desc "Build a target perspective."
      ::usage "build target [context-ref] [extra opts]"
      :fn (wrap-middlewares (fn [{:keys [opts resolved-config]}]
                              (let [{:keys [target context-ref]} opts
                                    output (user-commands/build! target context-ref resolved-config)
                                    serialize-fn (:serialize-fn (meta output))]
                                (print (serialize-fn output))
                                output))
                            [common-middlewares
                             middleware-resolve-config])
      ::cmd-spec {:target
                  {:desc "raw, links, map"
                   :coerce :keyword
                   :default "raw"
                   :required true}
      
                  ;; TODO: Rename this var...
                  :context-ref
                  {:desc "The context to use to build"
                   :coerce :string
                   :default "#**"
                   :required true}}
      :args->opts [:target :context-ref]}
     {:cmds ["cmd-build-info"]
      ::desc "Print out the build information of this tool."
      ::usage "build-info"
      :fn (wrap-middlewares (fn []
                              (pprint (utils/build-info)))
                            [common-middlewares])}
     {:cmds []
      :fn (wrap-middlewares identity
                            [common-middlewares])
      :opts {:help true}
      :spec (:spec common-cli-spec)}]))

(defn -main [& args]
  (tel/event! ::-main :trace)
  (cli/dispatch dispatch-table args))

(comment
  (tel/with-min-level :debug
    (-main "init" "--print-options"))

  (-main "build" "raw" "#**" "--base-dir" "/home/ds/perspects/ds@underties")
  (-main "--print-build-info"))
