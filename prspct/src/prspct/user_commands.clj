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
  (let [envelopes
        (vec (for [working-context    (:working-contexts resolved-config)
                   publish-to (get-in working-context [:config :np/publish-to])]
               (publication/context-publication-message-envelope working-context publish-to)))

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

(defmethod build! :raw
  [_ context-ref resolved-config]
  (let [resolved-contexts (:resolved-contexts resolved-config)
        parsed-context (ps/context->internal-context context-ref)
        matching-contexts (filterv #(resolution/ctx-match? parsed-context %) (keys resolved-contexts))
        matching-resolved-contexts (select-keys resolved-contexts matching-contexts)
        serialize-fn (fn [x] (with-out-str (pprint x)))]
    (with-meta matching-resolved-contexts {:serialize-fn serialize-fn})))

(defmethod build! :flat-ssh-keys
  [_ context-ref resolved-config]
  (let [matching-resolved-contexts
        (build! :raw context-ref resolved-config)

        idents
        (into #{}
              (comp
                (map (fn [[_context idents]] idents))
                cat
                (filter ps/identifier-ssh-key?)
                (filter ps/identifier-ssh-key->ssh-key))
              matching-resolved-contexts)

        serialize-fn
        (fn [idents] (with-out-str 
                       (doseq [ident idents]
                         (println ident))))]
    (with-meta idents {:serialize-fn serialize-fn})))

(defmethod build! :flat-emails
  [_ context-ref resolved-config]
  (let [matching-resolved-contexts
        (build! :raw context-ref resolved-config)

        idents
        (into #{}
              (comp
                (map (fn [[_context idents]] idents))
                cat
                (filter ps/identifier-email?)
                (filter ps/identifier-email->email))
              matching-resolved-contexts)

        serialize-fn
        (fn [idents] (with-out-str 
                       (doseq [ident idents]
                         (println ident))))]
    (with-meta idents {:serialize-fn serialize-fn})))

(defmethod build! :flat-uris
  [_ context-ref resolved-config]
  (let [matching-resolved-contexts
        (build! :raw context-ref resolved-config)

        idents
        (into #{}
              (comp
                (map (fn [[_context idents]] idents))
                cat
                (filter ps/identifier-uri?)
                (filter ps/identifier-uri->uri))
              matching-resolved-contexts)

        serialize-fn
        (fn [idents] (with-out-str 
                       (doseq [ident idents]
                         (println ident))))]
    (with-meta idents {:serialize-fn serialize-fn})))

(comment
  (fs/with-temp-dir [publish-dir {}]
    (fs/with-temp-dir [base-dir {}]
      (let [base-dir
            "/tmp/base-dir"

            edn 
            [(dsl/ctx "#" {:np/sources {:main 
                                        {:source/fn prspct.message-transfer/shell-source
                                         :source/args
                                         ["find" "/tmp/srv-dest" "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]}}
                                            
                           :np/publishers {:main
                                           {:publisher/fn 
                                            prspct.message-transfer/shell-publisher
                                            :publisher/args 
                                            ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" "/tmp/srv-dest" ";"]}}
                           :np/publish-to [{:publisher :main
                                            :as-from "alice <alice@example.com>"
                                            :as-id "email:alice@example.com"}]}
                                                
                  (dsl/ctx "irl.viet.hanoi" {}
                       (dsl/-> "email:bob@example.com" "#irl.viet.hanoi" :public)))]
        
            user-config-path
            (str base-dir "/prspct.edn")]

        (fs/create-dirs (fs/path base-dir))
        (when (not (fs/exists? user-config-path))
          (spit user-config-path (with-out-str 
                                   (binding [clojure.pprint/*print-right-margin* 120] 
                                     (pprint edn)))))
        (fs/create-dirs (fs/path base-dir ".prspct/fetches"))
        (when (not (fs/exists? (fs/path base-dir ".prspct/fetches" "0")))
          (fs/create-dirs (fs/path base-dir ".prspct/fetches" "0"))
          (spit (str (fs/path base-dir ".prspct/fetches.HEAD/fetch-info.edn")) {}))
        (when (not (fs/exists? (fs/path base-dir ".prspct/fetches.HEAD")))
          (fs/create-sym-link (fs/path base-dir ".prspct/fetches.HEAD")
                              (fs/path base-dir ".prspct/fetches" "0")))
        (-main "fetch" "--base-dir" base-dir)))))
