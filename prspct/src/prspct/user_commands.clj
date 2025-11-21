(ns prspct.user-commands
  (:require 
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]

    [cognitect.anomalies :as anom]

    [taoensso.telemere :as tel]
    [taoensso.truss :refer [have have! have!? have? ex-info!]]

    [malli.core :as m]

    [edamame.core :as edamame]

    [babashka.cli :as cli]
    [babashka.fs :as fs]

    [prspct.dsl :as dsl]
    [prspct.message-transfer :as message-transfer]
    [prspct.publication :as publication]
    [prspct.relation-graph :as rel-graph]
    [prspct.resolution :as resolution]
    [prspct.schemas :as ps])
  (:import
    java.time.Instant
    java.io.File
    java.nio.file.Files
    java.nio.file.StandardCopyOption
    java.nio.file.CopyOption)
  (:gen-class))

(defn swap-link! [link-path dst]
  (fs/with-temp-dir [tmp-dir {}]
    (let [tmp-link-path (fs/path tmp-dir "tmp-link")]
      (fs/create-sym-link tmp-link-path
                          dst)
      ;; we use java directly as babashka.fs is a bit weird with symlinks
      (java.nio.file.Files/move
        (fs/path tmp-link-path)
        (fs/path link-path)
        (into-array java.nio.file.CopyOption
                    [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))))

(defn fetch! [resolved-config current-fetch-link fetches-dir]
  (let [source-configs
        (into #{}
              (comp
                (map :config)
                (map :np/sources)
                (map vals)
                cat
                (filter identity))
              (:working-contexts resolved-config))

        fetch-output-dir
        (str (fs/canonicalize (fs/path fetches-dir (str (System/currentTimeMillis)))))

        _ (tel/event! ::fetch!:fetch-start)

        fetch-info
        (message-transfer/fetch! source-configs fetch-output-dir)

        _ (tel/event! ::fetch!:fetch-complete)

        overall-success 
        (every? :fetch-info-source/success? (-> fetch-info :fetch-info/sources vals))

        previous-fetch-head
        (fs/canonicalize current-fetch-link)]

    (swap-link! current-fetch-link fetch-output-dir)

    (tel/event! ::fetch!:swapped-current-fetch-link)

    (when-not overall-success
      (ex-info! "Unsuccessful fetch, unable to fetch from some or all sources." 
                {::anom/category ::anom/unavailable 
                 :fetch-info fetch-info
                 :instructions
                 (with-out-str
                   (println (str "See `" current-fetch-link "/fetch-info.edn` for more info."))
                   (println "To revert run:")
                   (println (str "rm '" current-fetch-link "' && ln -s '" previous-fetch-head "' '" current-fetch-link "' ;")))}))))


(defn publish! [resolved-config]
  (let [publish-instant
        (java.time.Instant/now)

        publish-to->working-contexts
        (-> (group-by first
              (for [working-context    (:working-contexts resolved-config)
                    publish-to-config (get-in working-context [:config :np/publish-to])]
                (let [publisher (get-in working-context [:config :np/publishers (:publisher publish-to-config)])
                      publish-to-config (assoc publish-to-config :publisher (have! publisher))]
                  [publish-to-config working-context])))
            (update-vals (fn [xs] (into #{} (map second) xs))))

        envelopes
        (into []
          (comp
            (map (fn [[publish-to-config working-contexts]]
                   (let [self-identifier
                         (publication/publish-to-config-ssh-key-id publish-to-config)

                         relations
                         (into []
                               (comp
                                 (mapcat (partial publication/publishable-relations self-identifier))
                                 (distinct))
                               working-contexts)]
                     (when relations
                       (->> relations
                            (publication/relations-publication publish-to-config publish-instant)
                            (publication/sign-publication publish-to-config)
                            (publication/publication-message publish-to-config)
                            (publication/publication-message-envelope (:publisher publish-to-config)))))))
            (filter identity))
          publish-to->working-contexts)

        _ (tel/event! ::publish!:produced-envelopes)

        publish-info
        (fs/with-temp-dir [envelopes-dir {}]
          (let [envelopes-dir (str envelopes-dir)
                publisher-config->input-dir (message-transfer/write-edn-message-envelopes! envelopes envelopes-dir)]
            (message-transfer/publish! publisher-config->input-dir envelopes-dir)))

        _ (tel/event! ::publish!:published-envelopes)

        overall-success
        (every? :publish-info-publisher/success? (-> publish-info :publish-info/publishers vals))]

      (tel/spy! :debug publish-info)
      (when-not overall-success
        (ex-info! "Unsuccessful publish, failed to publish to some or all publishers."
                  {::anom/category ::anom/unavailable
                   :publish-info publish-info}))))
                   

(defmulti build! (fn [target context-ref resolved-config] target))

(defmethod build! :edn
  [_ context-ref resolved-config]
  (let [resolved-contexts 
        (:resolved-contexts resolved-config)

        parsed-context
        (ps/context->internal-context context-ref)

        matching-contexts 
        (filterv #(resolution/ctx-match? parsed-context %) 
                 (keys resolved-contexts))

        matching-resolved-contexts 
        (select-keys resolved-contexts matching-contexts)

        serialize-fn
        (fn [x] (with-out-str (pprint x)))]
    (with-meta matching-resolved-contexts {:serialize-fn serialize-fn})))

(defn- build-flat [context-ref resolved-config filter-f]
  (let [matching-resolved-contexts
        (build! :edn context-ref resolved-config)

        idents
        (into #{}
              (comp
                (map (fn [[_context pairs]] pairs))
                cat
                (map (fn [[ident _context]] ident))
                (filter filter-f)
                (map ps/identifier-value))
              matching-resolved-contexts)

        serialize-fn
        (fn [idents] (with-out-str 
                       (doseq [ident idents]
                         (println ident))))]
    (with-meta idents {:serialize-fn serialize-fn})))

(defmethod build! :flat-ssh-keys
  [_ context-ref resolved-config]
  (build-flat context-ref resolved-config ps/identifier-ssh-key?))

(defmethod build! :flat-emails
  [_ context-ref resolved-config]
  (build-flat context-ref resolved-config ps/identifier-email?))

(defmethod build! :flat-uris
  [_ context-ref resolved-config]
  (build-flat context-ref resolved-config ps/identifier-uri?))

(defmethod build! :tsv
  [_ context-ref resolved-config]
  (let [matching-resolved-contexts
        (build! :edn context-ref resolved-config)

        lines
        (into [] 
              (comp
                (map 
                  (fn [[sub-context member-pairs]]
                    (mapv
                      (fn [[obj-identifier obj-context]]
                        [(ps/internal-context->context sub-context)
                         (ps/internal-context->context obj-context)
                         (ps/identifier-dispatch obj-identifier)
                         (ps/identifier-value obj-identifier)])
                      member-pairs)))
                cat)
              matching-resolved-contexts)

        serialize-fn
        (fn [lines] (with-out-str 
                      (doseq [line lines]
                        (apply println line))))]
    (with-meta lines {:serialize-fn serialize-fn})))
