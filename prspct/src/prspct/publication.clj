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


;; TODO: Clean up this filled vs not filled issue
(m/=> publish-identity->ssh-key-id [:=> [:cat #'ps/PublishIdentity] #'ps/Identifier])
(defn publish-identity->ssh-key-id [publish-identity]
  (-> publish-identity :ssh-key-id/public-key-path slurp ps/ssh-public-key->identifier-ssh))

(def ssh-signature-namespace "net-perspective")

(defn ssh-signature [s private-key-path]
  (have! private-key-path)
  (let [ret
        (utils/ssh-keygen 
          "-Y" "sign" 
          "-n" ssh-signature-namespace 
          "-f" private-key-path
          :in s)]
    (utils/assert-sh-ret ret "publication-signing")
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
(defn sign-publication [publish-identity publication]
  (have! #(not (contains? % :publication/signature)) publication)
  (let [publication-string 
        (ps/encode-edn-message-body #'ps/PublicationMessage publication)
        
        id
        (publish-identity->ssh-key-id publish-identity)

        hash
        (utils/sha256 publication-string)
    
        ssh-signature
        (ssh-signature publication-string (-> publish-identity :ssh-key-id/private-key-path))

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


(m/=> publishable-relations [:=> [:cat #'ps/Identifier #'ps/WorkingContext] [:vector #'ps/Relation]])
(defn publishable-relations [self-identifier working-context]
  (let [{:keys [context relations]} working-context]
    (into []
          (comp
            (filter
              (fn [rel]
                (and (not (keyword? (first (:relation/object-pair rel))))
                     (:relation/public? rel))))
            (map
              (fn [rel] 
                (assoc rel :relation/subject-pair [self-identifier context]))))
          relations)))

(defn publish-identity->from [publish-identity]
  (str (:name publish-identity) " <" (:email publish-identity) ">"))

(m/=> relations-publication 
      [:=> [:cat #'ps/PublishConfig #'ps/PublishIdentity :time/instant [:vector #'ps/Relation]]
       #'ps/Publication])
(defn relations-publication [publish-config publish-identity ^Instant publish-instant relations]
  #:publication{:from (publish-identity->from publish-identity)
                :version :alpha-do-not-spread
                :valid-from publish-instant
                :valid-until (.plus 
                               publish-instant 
                               ^int (:publication-validity-seconds publish-config) 
                               ChronoUnit/SECONDS)
                :invalidates-previous-publications-until publish-instant
                :self-identifier (publish-identity->ssh-key-id publish-identity)
                :relations relations})

(m/=> publication-message [:=> [:cat #'ps/PublishIdentity #'ps/Publication]
                               #'ps/PublicationMessage])
(defn publication-message [publish-identity publication]
  {:headers {:from (publish-identity->from publish-identity)
             :subject "Publication"
             :date (utils/instant->offset-date-time (:publication/valid-from publication))
             :copyright (publish-identity->from publish-identity)
             ;; TODO: Licensing setting, needs to be mandatory to upload to certain places...
             :license "AGPL"
             :x-np "net-perspective.org"
             :x-np-client "prspct"
             :x-np-id (publish-identity->ssh-key-id publish-identity)
             :x-np-timestamp (:publication/valid-from publication)
             :x-np-intent :publication}
   :body publication})

(m/=> publication-message-envelope [:=> [:cat #'ps/MessagePublisherConfig #'ps/PublicationMessage] 
                                        [:maybe #'ps/EDNMessageEnvelope]])
(defn publication-message-envelope [publisher publication-message]
    {:publisher publisher
     :message publication-message
     :message-schema ps/PublicationMessage})
