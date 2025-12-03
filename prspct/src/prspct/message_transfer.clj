(ns prspct.message-transfer
  [:require 
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]

   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [malli.core :as m]
   [malli.generator :as mg]

   [edamame.core :as edamame]

   [babashka.fs :as fs]

   [prspct.lib.utils :as utils]
   [prspct.schemas :as ps]]
  [:import
   [java.time Instant]])



;; ## Protocols

(defprotocol MessageSource
  (-source-config [this])
  ;; TODO: In the future add some way to report on progress.
  (-source-fetch! [this output-dir]))

(m/=> source-config [:=> [:cat :any] #'ps/MessageSourceConfig])
(defn source-config
  [message-source]
  (-source-config message-source))

(m/=> source-fetch! [:=> [:cat :any :string] #'ps/MessageTransferResult])
(defn source-fetch!
  "Fetches all messages into output-dir"
  [publication-message-source output-dir]
  (-source-fetch! publication-message-source output-dir))

(defprotocol MessagePublisher
  (-publisher-config [this])
  (-publisher-publish! [this input-dir]))

(m/=> publisher-config [:=> [:cat :any] #'ps/MessagePublisherConfig])
(defn publisher-config [this]
  (-publisher-config this))

(m/=> publisher-publish! [:=> [:cat :any :string] ps/MessageTransferResult])
(defn publisher-publish! [this input-dir]
  (-publisher-publish! this input-dir))


;; ## Implementations

;; TODO: Add error code
(defn- shell-transfer [args]
  (try
    ;; TODO: Use *sh-cwd*
    (let [res (apply shell/sh args)]
      {:transfer-result/success? (zero? (:exit res))
       :transfer-result/exception nil
       :transfer-result/out (:out res)
       :transfer-result/err (:err res)})
    (catch Exception e
      {:transfer-result/success? false
       :transfer-result/exception e
       :transfer-result/out ""
       :transfer-result/err ""})))

(defn shell-source 
  "Provide args to provide to clojure.java.shell/sh

  In args, :output-dir keyword will be substituted."
  [source-config]
  (reify MessageSource
    (-source-config [_] 
      source-config)
    (-source-fetch! [_ output-dir]
      (fs/create-dirs output-dir)
      (let [args
            (:shell/args source-config)

            substitute-f 
            (fn [arg]
              (if (= arg :output-dir)
                (str output-dir)
                arg))

            args' 
            (mapv substitute-f args)]
        (shell-transfer args')))))

(defn shell-publisher 
  "Provide args to provide to clojure.java.shell/sh

  In args, :input-dir, :input-dir-slash-dot keywords will be substituted.
  The expectation is to publish all .eml files.
  "
  [publisher-config]
  (reify MessagePublisher
    (-publisher-config [_] 
      publisher-config)
    (-publisher-publish! [_ input-dir]
      (let [args
            (:shell/args publisher-config)

            substitute-f 
            (fn [arg]
              (cond 
                (= :input-dir arg)
                input-dir

                (= :input-dir-slash-dot arg)
                (str input-dir "/.")

                :else
                arg))

            args' 
            (mapv substitute-f args)]
        (shell-transfer args')))))


;; ## Implementation dispatching

(def source-config-dispatch->implementation
  {:shell #'shell-source})

(defn source-config->source [source-config]
  (let [dispatch (ps/message-source-config-dispatch source-config)
        impl-fn (source-config-dispatch->implementation dispatch)]
    (have! impl-fn)
    (impl-fn source-config)))

(def publisher-config-dispatch->implementation
  {:shell #'shell-publisher})

(defn publisher-config->publisher [publisher-config]
  (let [dispatch (ps/message-publisher-config-dispatch publisher-config)
        impl-fn (publisher-config-dispatch->implementation dispatch)]
    (have! impl-fn)
    (impl-fn publisher-config)))



;; ## Message transfer

(defn- new-fetch-info [source-configs]
  {:fetch-info/uuid
     (random-uuid)

     :fetch-info/time-start
     (Instant/now)

     :fetch-info/sources
     (into {}
          (map (fn [source-config]
                [source-config 
                 {:fetch-info-source/output-dir (str (hash source-config))
                  :fetch-info-source/status :not-started}]))
          source-configs)})

(defn fetch! 
  ([source-configs output-dir]
   (fetch! source-configs output-dir (atom nil)))
   
  ([source-configs output-dir fetch-info-atom]
   (fs/create-dirs output-dir)
   (reset! fetch-info-atom (new-fetch-info source-configs))
   (doseq [source-config source-configs]
    (let [source-output-dir 
          (str output-dir 
               "/" 
               (get-in @fetch-info-atom 
                       [:fetch-info/sources source-config :fetch-info-source/output-dir]))

          source (source-config->source source-config)

          assoc-source-fetch!
          (fn [k v] (swap! fetch-info-atom assoc-in [:fetch-info/sources source-config k] v))]

      (assoc-source-fetch! :fetch-info-source/status :running)
      (assoc-source-fetch! :fetch-info-source/time-start (Instant/now))
      (let [res (source-fetch! source source-output-dir)]
        (assoc-source-fetch! :fetch-info-source/time-finish (Instant/now))
        (assoc-source-fetch! :fetch-info-source/transfer-result res)
        (assoc-source-fetch! :fetch-info-source/success? (:transfer-result/success? res)))
      (assoc-source-fetch! :fetch-info-source/status :finished)))
   (swap! fetch-info-atom assoc :fetch-info/time-finish (Instant/now))
   ;; TODO: duratom?
   (spit (str output-dir "/fetch-info.edn") (ps/encode-fetch-info @fetch-info-atom))
   @fetch-info-atom))

          
(m/=> load-fetch [:=> [:cat :string] [:seqable #'ps/AnyMessage]])
(defn load-fetch 
  "Loads and parses a fetch into a seq of any-messages.

  ::publication-message? is true if it has been parsed as a publication message.
  ::publication-message? is false if it can't be parsed, if possible the message
  is parsed as a simple-message and included in the body.
  ::publication-message-exception and ::simple-message-exception will be attached
  if they can't be parsed respectively."
  [fetch-dir]
  (let [fetch-info-path (str fetch-dir "/fetch-info.edn")
        fetch-info (-> fetch-info-path slurp ps/decode-fetch-info)]
    (for [[_ fetch-info-source] (:fetch-info/sources fetch-info)
          :let [output-dir (str fetch-dir "/" (:fetch-info-source/output-dir fetch-info-source))]
          file (map str (fs/glob output-dir "**.eml"))]
      (let [contents 
            (slurp file)

            message
            (try
              (-> (ps/eml->edn-message ps/PublicationMessage contents)
                  (assoc-in [:headers ::publication-message?] true))
              (catch Exception publication-message-exception
                (try
                  {:headers {::publication-message? false
                             ::publication-message-exception publication-message-exception}
                   :body (ps/eml->simple-message contents)}
                  (catch Exception simple-message-exception
                    {:headers {::publication-message? false
                               ::publication-message-exception publication-message-exception
                               ::simple-message-exception simple-message-exception}
                     :body contents}))))]
        (-> message
            (assoc-in [:headers ::fetch-dir] fetch-dir)
            (assoc-in [:headers ::file-path] file))))))


(defn write-edn-message! [message-schema message output-dir]
  (let [eml (ps/edn-message->eml message-schema message)
        ;; TODO: Maybe can hash only some of the fields instead of all of them.
        eml-hash   (utils/sha256 eml)
        filename (str eml-hash ".eml")
        write-path (str output-dir "/" filename)]
      (spit write-path eml)
      write-path))

(comment
  (write-edn-message! #'ps/ExampleMessage (mg/generate ps/ExampleMessage) "/tmp/np-holding-space"))


;; TODO: Add content-type: text/edn or application/x-np-publication+edn
(m/=> write-edn-message-envelopes! [:=> [:cat [:vector ps/EDNMessageEnvelope] :string] 
                                        [:map-of ps/MessagePublisherConfig :string]])
(defn write-edn-message-envelopes! [edn-message-envelopes output-dir]
  (into {}
        (map (fn [[publisher envelopes]]
               (let [publisher-output-dir (str output-dir "/" (hash publisher))]
                 (fs/create-dirs publisher-output-dir)
                 (doseq [{:keys [message-schema message]} envelopes]
                   (write-edn-message! message-schema message publisher-output-dir))
                 [publisher publisher-output-dir])))
        (group-by :publisher edn-message-envelopes)))

(comment
  (fs/create-dirs ps/example-publications-dir)
  (write-edn-message-envelopes! (mg/generate [:vector ps/EDNMessageEnvelope]) "/tmp/np-holding-space"))


(defn new-publish-info [publisher-config->input-dir]
  {:publish-info/uuid
   (random-uuid)

   :publish-info/time-start
   (Instant/now)

   :publish-info/publishers
   (into {}
         (map (fn [[publisher-config input-dir]]
                [publisher-config 
                 {:publish-info-publisher/input-dir input-dir
                  :publish-info-publisher/status :not-started}]))
         publisher-config->input-dir)})

(defn publish! 
  ([publisher-config->input-dir parent-input-dir]
   (publish! publisher-config->input-dir parent-input-dir (atom nil)))
  ([publisher-config->input-dir parent-input-dir publish-info-atom]
   (reset! publish-info-atom (new-publish-info publisher-config->input-dir))
   (doseq [[publisher-config input-dir] publisher-config->input-dir]
     (let [publisher (publisher-config->publisher publisher-config)
           assoc-publisher-publish!
           (fn [k v] (swap! publish-info-atom assoc-in [:publish-info/publishers publisher-config k] v))]
           
       (assoc-publisher-publish! :publish-info-publisher/status :running)
       (assoc-publisher-publish! :publish-info-publisher/time-start (Instant/now))
       (let [res (publisher-publish! publisher input-dir)]
         (assoc-publisher-publish! :publish-info-publisher/time-finish (Instant/now))
         (assoc-publisher-publish! :publish-info-publisher/transfer-result res)
         (assoc-publisher-publish! :publish-info-publisher/success? (:transfer-result/success? res)))
       (assoc-publisher-publish! :publish-info-publisher/status :finished)))
   (swap! publish-info-atom assoc :publish-info/time-finish (Instant/now))
   (spit (str parent-input-dir "/publish-info-" (get @publish-info-atom :publish-info/uuid) ".edn") @publish-info-atom)
   @publish-info-atom))
