(ns prspct.user-commands
  (:require 
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]

    [cognitect.anomalies :as anom]
    [taoensso.telemere :as tel]
    [taoensso.truss :refer [have have! have!? have? ex-info!]]

    [babashka.fs :as fs]

    [prspct.dsl :as dsl]
    [prspct.message-transfer :as message-transfer]
    [prspct.publication :as publication]
    [prspct.resolution :as resolution]
    [prspct.schemas :as ps]
    [prspct.lib.utils :as utils])
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
        (set (vals (get-in resolved-config [:user-config-options :sources])))

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
                   (println
                     (str/join 
                       "\n"
                       (into []
                             (comp
                               (filter (fn [[_source fetch-info-source]]
                                         (-> fetch-info-source :fetch-info-source/success? not)))
                               (map (fn [[source fetch-info-source]]
                                      (with-out-str
                                        (println "---------------------------")
                                        (println "Source:")
                                        (pprint source)
                                        (println)
                                        (println "out:")
                                        (println 
                                          (-> fetch-info-source 
                                              :fetch-info-source/transfer-result
                                              :transfer-result/out))
                                        (println "err:")
                                        (println 
                                          (-> fetch-info-source 
                                              :fetch-info-source/transfer-result
                                              :transfer-result/err))))))
                             (:fetch-info/sources fetch-info))))
                   (println (str "See `" current-fetch-link "/fetch-info.edn` for more info."))
                   (println "To revert run:")
                   (println (str "rm '" current-fetch-link "' && ln -s '" previous-fetch-head "' '" current-fetch-link "' ;")))}))))


(defn envelopes [resolved-config publish-instant]
  (let [publish-config-keyword->working-contexts
        (utils/multigroup-by 
          (fn [working-context]
            (or
              (get-in working-context 
                      [:context-config :publish-configs])
              (get-in resolved-config 
                      [:user-config-options :default-publish-configs]
                      [])))
          (:working-contexts resolved-config))

        envelopes
        (into []
              (comp
                (map (fn [[publish-config-keyword working-contexts]]
                       (let [publish-config
                             (get-in resolved-config
                                     [:user-config-options :publish-configs publish-config-keyword])

                             publish-identity
                             (get-in resolved-config
                                     [:user-config-options :publish-identities (:identity publish-config)])

                             publisher
                             (get-in resolved-config
                                     [:user-config-options :publishers (:publisher publish-config)])

                             self-identifier
                             (publication/publish-identity->ssh-key-id publish-identity)

                             relations
                             (into []
                                   (comp
                                     (mapcat (partial publication/publishable-relations self-identifier))
                                     (distinct))
                                   working-contexts)]
                         (when relations
                           (->> relations
                                (publication/relations-publication 
                                  publish-config 
                                  publish-identity 
                                  publish-instant)
                                (publication/publication-message publish-identity)
                                (publication/sign-publication-message publish-identity)
                                (publication/publication-message-envelope publisher))))))
                (filter identity))
              publish-config-keyword->working-contexts)]
    envelopes))

(defn publications! [resolved-config now-instant publications-output-dir]
  (let [envelopes (envelopes resolved-config now-instant)]
    (tel/event! ::publications!:produced-envelopes)
    (message-transfer/write-edn-message-envelopes! envelopes publications-output-dir)
    (tel/event! ::publications!:wrote-envelopes)))

(defn publish! [resolved-config now-instant last-publish-info-path]
  (let [envelopes
        (envelopes resolved-config now-instant)

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
      (spit last-publish-info-path 
            (with-out-str (pprint publish-info)))
      (when-not overall-success
        (ex-info! "Unsuccessful publish, failed to publish to some or all publishers."
                  {::anom/category ::anom/unavailable
                   :publish-info publish-info

                   :instructions
                   (with-out-str
                     (println
                       (str/join "\n"
                         (into []
                               (comp
                                 (filter (fn [[_publisher publish-info-publisher]]
                                           (-> publish-info-publisher :publish-info-publisher/success? not)))
                                 (map (fn [[publisher publish-info-publisher]]
                                        (with-out-str
                                          (println "---------------------------")
                                          (println "Publisher:")
                                          (pprint publisher)
                                          (println)
                                          (println "out:")
                                          (println 
                                            (-> publish-info-publisher 
                                                :publish-info-publisher/transfer-result
                                                :transfer-result/out))
                                          (println "err:")
                                          (println 
                                            (-> publish-info-publisher 
                                                :publish-info-publisher/transfer-result
                                                :transfer-result/err))))))
                               (:publish-info/publishers publish-info))))
                     (println "---------------------------")
                     (println)
                     (println (str "See `" last-publish-info-path "` for more info.")))}))))
                    
(defn expand-idents [resolved-self-contexts idents]
  (into idents
        (comp
          (filter ps/include-ident?)
          (mapcat (partial resolution/include-ident->idents resolved-self-contexts)))
        idents))

(defmulti build! (fn [target _build-context _build-idents _resolved-config] target))

(defmethod build! :edn
  [_ build-context build-idents resolved-config]
  (let [resolved-self-contexts 
        (:resolved-self-contexts resolved-config)

        expanded-build-idents
        (expand-idents resolved-self-contexts build-idents)

        resolved-contexts
        (if (= #{:self} build-idents)
          resolved-self-contexts
         (resolution/relgraph->resolved-contexts
           (:relgraph resolved-config)
           expanded-build-idents))

        parsed-context
        (ps/context->internal-context build-context)

        matching-contexts 
        (filterv #(resolution/ctx-match? parsed-context %) 
                 (keys resolved-contexts))

        matching-resolved-contexts 
        (select-keys resolved-contexts matching-contexts)

        serialize-fn
        (fn [x] (with-out-str (pprint x)))]
    (with-meta matching-resolved-contexts {:serialize-fn serialize-fn})))

(defn- build-flat [build-context build-idents resolved-config filter-f]
  (let [matching-resolved-contexts
        (build! :edn build-context build-idents resolved-config)

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
  [_ build-context build-idents resolved-config]
  (build-flat build-context build-idents resolved-config ps/identifier-ssh-key?))

(defmethod build! :flat-emails
  [_ build-context build-idents resolved-config]
  (build-flat build-context build-idents resolved-config ps/identifier-email?))

(defmethod build! :flat-uris
  [_ build-context build-idents resolved-config]
  (build-flat build-context build-idents resolved-config ps/identifier-uri?))

(defmethod build! :tsv
  [_ build-context build-idents resolved-config]
  (let [matching-resolved-contexts
        (build! :edn build-context build-idents resolved-config)

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

(defmethod build! :relations.edn
  [_ build-context build-idents resolved-config]
  (let [matching-resolved-contexts
        (build! :edn build-context build-idents resolved-config)

        expanded-build-idents
        (expand-idents (:resolved-self-contexts resolved-config) build-idents)

        serialize-fn
        (fn [ctxs] 
          (with-out-str 
            (println ";; resolved relations for:")
            (doseq [ident expanded-build-idents]
              (println ";; -" ident))
            (doseq [ctx ctxs]
             (println)
             (binding [clojure.pprint/*print-right-margin* 150]
               (pprint ctx)))))

        dsl
        (mapv 
          (fn [[context object-pairs]]
            (apply
              dsl/ctx 
              (ps/internal-context->context context)
              (mapv
                (fn [[obj-ident obj-context]]
                  (dsl/-> obj-ident (ps/internal-context->context obj-context) :public))
                object-pairs)))
          
          matching-resolved-contexts)]
    (with-meta dsl {:serialize-fn serialize-fn})))
