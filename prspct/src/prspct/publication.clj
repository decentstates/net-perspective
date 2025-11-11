(ns prspct.publication
  [:require 
   [clojure.string :as str]

   [malli.core :as m]

   [prspct.schemas :as ps]
   [prspct.lib.utils :as utils]]
  [:import
   [java.time Instant]
   [java.time.temporal ChronoUnit]])


;; TODO: Currently just a stub, likely change to sign the publication instead of the message?
(defn sign-edn-message [publish-to-config msg header-fields-to-sign]
  ;; should be dkim compatible
  ;; built headers and body to be signed, performing encoding, unparsing.
  ;; sign fields
  "v=-1;... unimplemented")

(m/=> publish-to-config->as-id [:=> [:cat #'ps/PublishToConfig] #'ps/Identifier])
(defn publish-to-config->as-id [publish-to-config]
  (cond 
    (contains? publish-to-config :as-id)
    (:as-id publish-to-config)
    
    (contains? publish-to-config :as-ssh-key)
    (let [{:keys [public-key-path]} (:as-ssh-key publish-to-config)
          ssh-public-key (-> (slurp public-key-path))])))
  

(m/=> context-publication [:=> [:cat #'ps/WorkingContext #'ps/PublishToConfig] #'ps/Publication])
(defn context-publication [working-context publish-to-config]
  (let [ ;; TODO: Apply default in config:
        valid-days ^int (or (get-in working-context [:config :np/publication-valid-days])
                            30)
        valid-from (Instant/now)
        valid-until (.plus (Instant/now) ^int valid-days ChronoUnit/DAYS)

        self-identifier (:as-id publish-to-config)
        context (:context working-context)
        relations (mapv 
                    #(assoc % :relation/subject-pair [self-identifier context])
                    (:relations working-context))
        relations (filterv
                    (fn [rel]
                      (not (keyword? (first (:relation/object-pair rel)))))
                    relations)]
    #:publication{:version :alpha-do-not-spread
                  :valid-from valid-from
                  :valid-until valid-until
                  :self-identifier self-identifier
                  :relations relations}))

(m/=> context-publication-message [:=> [:cat #'ps/WorkingContext #'ps/PublishToConfig #'ps/Publication]
                                       #'ps/PublicationMessage])
(defn context-publication-message [working-context publish-to-config publication]
  (let [context (ps/internal-context->context (:context working-context))
        from (:as-from publish-to-config)
        id   (:as-id publish-to-config)
        publication-timestamp (:publication/valid-from publication)

        publication-message
        {:headers {:from from
                   :subject (str "Publication: " context)
                   :date (utils/instant->offset-date-time publication-timestamp)
                   ;; TODO: is this possible?
                   :copyright from
                   :license "Not for commercial use."
                   :x-np "net-perspective.org"
                   :x-np-client "prspct"
                   :x-np-id id
                   :x-np-context context
                   :x-np-timestamp publication-timestamp
                   :x-np-intent :publication}
         :body publication}]
    (assoc-in publication-message 
              [:headers :x-np-signature]
              (sign-edn-message 
                publish-to-config 
                publication-message
                [:x-np-client :x-np-id :x-np-timestamp :x-np-intent]))))

(m/=> context-publication-message-envelope [:=> [:cat #'ps/WorkingContext #'ps/PublishToConfig] 
                                                [:maybe #'ps/EDNMessageEnvelope]])
(defn context-publication-message-envelope [working-context publish-to-config]
  (let [publishers (get-in working-context [:config :np/publishers])
        publisher (get publishers (:publisher publish-to-config))]
    (if-not publisher 
      nil
      {:publisher 
       publisher 

       :message 
       (context-publication-message 
         working-context 
         publish-to-config
         (context-publication working-context publish-to-config))
     
       :message-schema
       ps/PublicationMessage})))
