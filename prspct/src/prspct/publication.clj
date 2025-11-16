(ns prspct.publication
  [:require 
   [clojure.string :as str]
   [clojure.java.shell :as shell]

   [taoensso.telemere :as tel]
   [cognitect.anomalies :as anom]
   [taoensso.truss :refer [have have! have!? have? ex-info! unexpected-arg!]]

   [babashka.fs :as fs]

   [malli.core :as m]

   [prspct.schemas :as ps]
   [prspct.lib.utils :as utils]]
  [:import
   [java.time Instant]
   [java.time.temporal ChronoUnit]])


;; REVIEW: Need multiple ids per publication, or publications per id.


(m/=> publish-to-config-ssh-key-id [:=> [:cat #'ps/PublishToConfig] #'ps/Identifier])
(defn publish-to-config-ssh-key-id [publish-to-config]
  (-> publish-to-config :ssh-key-id/public-key-path slurp ps/ssh-public-key->identifier-ssh))

(def ssh-signature-namespace "net-perspective")

(defn ssh-signature [s private-key-path]
  (have! private-key-path)
  (let [ret
        (utils/ssh-keygen 
          "-Y" "sign" 
          "-n" ssh-signature-namespace 
          "-f" private-key-path
          :in s)]
    (utils/assert-sh-ret ret)
    (:out ret)))

(defn verify-ssh-signature [s signature public-key]
  (fs/with-temp-dir [d {}]
    (let [public-key-path (str d "/public-key")
          signature-path (str d "/signature")]
      (spit public-key-path public-key)
      (spit signature-path signature)
      (let [ret (utils/ssh-keygen 
                  "-Y" "check-novalidate" 
                  "-n" ssh-signature-namespace 
                  "-f" public-key-path 
                  "-s" signature-path
                  :in s)]
        (if-not (= (:exit ret) 0)
          (do
            (tel/spy! {:level :debug
                       :id ::verify-ssh-signature:non-zero-exit}  
                      ret)
            false)
          (str/starts-with? (:out ret)
                            (str "Good \"" ssh-signature-namespace "\" signature")))))))

(comment
  (utils/with-temp-key-pairs [pair {}]
    (let [sig (ssh-signature "test" (:private pair))]
      (verify-ssh-signature
        "test"
        sig
        (:public pair)))))

;; Very rough draft, based off of dkim
(defn sign-publication [publish-to-config publication]
  (have! #(not (contains? % :publication/signature)) publication)
  (let [publication-string 
        (ps/encode-edn-message-body #'ps/PublicationMessage publication)
        
        id
        (publish-to-config-ssh-key-id publish-to-config)

        hash
        (utils/sha256 publication-string)
    
        ssh-signature
        (ssh-signature publication-string (-> publish-to-config :ssh-key-id/private-key-path))

        publication-signature
        (m/coerce #'ps/PublicationSignature
          {:version "alpha"
           :id id
           :hash-algorithm "sha256"
           :hash hash
           :signature-method "ssh-sign-whole-msg"
           :signature ssh-signature})
        
        encoded-publication-signature
        (ps/encode-publication-signature publication-signature)]
    (assoc publication :publication/signature  encoded-publication-signature)))

(defn verify-publication [publication]
  (let [publication-without-signature
        (dissoc publication :publication/signature)

        publication-string 
        (ps/encode-edn-message-body #'ps/PublicationMessage publication-without-signature)
        
        publication-signature
        (-> publication :publication/signature ps/decode-publication-signature)

        valid-publication-signature?
        (m/validate #'ps/PublicationSignature publication-signature)]

    (if-not valid-publication-signature?
      {:valid? false
       :issues [:invalid-publication-signature]}
      (let [public-key
            (-> publication-signature :id ps/identifier-ssh-key->ssh-key)

            matching-self-identifier?
            (= (:publication/self-identifier publication)
               (:id publication-signature))

            ssh-signature
            (-> publication-signature :signature)]
        (if-not matching-self-identifier?
          {:valid? false :issues [:non-matching-self-identifier]}
          (if-not (verify-ssh-signature publication-string ssh-signature public-key)
            {:valid? false :issues [:failed-signature-verification]}
            {:valid? true}))))))

(comment
  (require '[malli.generator :as mg])
  (let [publication (mg/generate #'ps/Publication)]
    (utils/with-temp-key-pairs [pair {}]
      (let [publication
            (-> publication
                (assoc :publication/relations [])
                (dissoc :publication/signature))

            publish-to-config 
            {:publisher :main
             :name "Alice"
             :email "alice@example.com"
             :ssh-key-id/public-key-path (:public pair)
             :ssh-key-id/private-key-path (:private pair)}

            signed-publication
            (sign-publication publish-to-config publication)

            signed-publication-0
            (sign-publication publish-to-config (dissoc publication :publication/signature))

            signed-publication-1
            (sign-publication publish-to-config publication)]
        (verify-publication signed-publication)
        [signed-publication-0
         signed-publication-1])))
  (utils/with-temp-key-pairs [pair {}]
    (let [publication
          #:publication{:version :alpha-do-not-spread, 
                        :valid-from "1970-01-01T00:00:00Z",
                        :valid-until "1970-01-01T00:00:00Z",
                        :self-identifier "email:u-0000@example.com",
                        :relations []}

          publication
          (-> publication
              (dissoc :publication/signature)
              (assoc :publication/self-identifier
                     (ps/ssh-public-key->identifier-ssh (slurp (:public pair)))))

          publish-to-config 
          {:publisher :main
           :name "Alice"
           :email "alice@example.com"
           :ssh-key-id/public-key-path (:public pair)
           :ssh-key-id/private-key-path (:private pair)}

          signed-publication
          (sign-publication publish-to-config publication)]
      (verify-publication signed-publication))))


(m/=> context-publication [:=> [:cat #'ps/WorkingContext #'ps/PublishToConfig] #'ps/Publication])
(defn context-publication [working-context publish-to-config]
  (let [
        ;; TODO: Apply default in config:
        valid-days ^int (or (get-in working-context [:config :np/publication-valid-days])
                            30)
        valid-from (Instant/now)
        valid-until (.plus (Instant/now) ^int valid-days ChronoUnit/DAYS)

        self-identifier (publish-to-config-ssh-key-id publish-to-config)
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
  (let [context
        (ps/internal-context->context (:context working-context))

        from
        (str (:name publish-to-config) " <" (:email publish-to-config) ">")

        id
        (publish-to-config-ssh-key-id publish-to-config)

        publication-timestamp
        (:publication/valid-from publication)

        signed-publication
        (sign-publication publish-to-config publication)

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
         :body signed-publication}]
    publication-message))

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
