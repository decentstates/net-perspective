(ns prsp.publication
  [:require
   [clojure.string :as str]

   [cognitect.anomalies :as anom]
   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info! unexpected-arg!]]

   [babashka.fs :as fs]

   [malli.core :as m]

   [prsp.schemas :as ps]
   [prsp.lib.utils :as utils]]
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

(defn sign-publication-message [publish-identity publication-message]
  (have! #(not (contains? % :x-np-signature)) (:headers publication-message))
  (let [publication-string
        (->> publication-message
             (ps/edn-message->eml ps/PublicationMessage)
             ps/eml->simple-message
             :body)

        id
        (publish-identity->ssh-key-id publish-identity)

        hash
        (utils/sha256 publication-string)

        ssh-signature
        (ssh-signature publication-string
                       (-> publish-identity :ssh-key-id/private-key-path))

        publication-signature
        (m/coerce #'ps/PublicationSignature
                  {:version "alpha"
                   :id id
                   :hash-algorithm "sha256"
                   :hash hash
                   :signature-method "ssh-keygen-sign-whole-message"
                   :signature ssh-signature})

        encoded-publication-signature
        (ps/encode-publication-signature publication-signature)]
    (assoc-in publication-message [:headers :x-np-signature] encoded-publication-signature)))

(defn verify-publication-message [publication-message]
  (let [publication-string
        (-> publication-message
            :headers
            :prsp.message-transfer/file-path
            slurp
            ps/eml->simple-message
            :body)

        publication-signature
        (ps/decode-publication-signature
         (get-in publication-message [:headers :x-np-signature]))

        valid-publication-signature?
        (m/validate #'ps/PublicationSignature publication-signature)]

    (if-not valid-publication-signature?
      {:valid? false
       :issues [:invalid-publication-signature]}
      (let [public-key
            (-> publication-signature :id ps/identifier-ssh-key->ssh-key)

            matching-self-identifier?
            (= (get-in publication-message [:body :publication/self-identifier])
               (:id publication-signature))

            ssh-signature
            (-> publication-signature :signature)]
        (if-not matching-self-identifier?
          {:valid? false :issues [:non-matching-self-identifier]}
          (if-not (verify-ssh-signature publication-string ssh-signature public-key)
            {:valid? false :issues [:failed-signature-verification]}
            {:valid? true}))))))

(m/=> publishable-relations [:=> [:cat #'ps/Identifier #'ps/WorkingContext] [:vector #'ps/Relation]])
(defn publishable-relations [self-identifier working-context]
  (let [{:keys [context relations]} working-context]
    (into []
          (comp
           (map
            (fn [rel]
              (if (= :self 
                     (first (:relation/object-pair rel)))
                  (assoc-in rel [:relation/object-pair 0] self-identifier)
                  rel)))
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
             :x-np-client "prsp"
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


;; ## Message filtering

;; TODO: test

(defn message-filter-valid-publication-message [publication-message]
  (get-in publication-message [:headers :prsp.message-transfer/publication-message?]))

(defn message-filter-valid-date [now-instant]
  (fn [publication-message]
    (and
     (or
      (.isBefore
       ^java.time.Instant (get-in publication-message [:body :publication/valid-from])
       ^java.time.Instant now-instant)
      (.equals
       ^java.time.Instant (get-in publication-message [:body :publication/valid-from])
       ^java.time.Instant now-instant))
     (.isBefore
      ^java.time.Instant now-instant
      ^java.time.Instant (get-in publication-message [:body :publication/valid-until])))))

(defn message-filter-valid-signature
  [publication-message]
  (let [ret (prsp.publication/verify-publication-message publication-message)]
    (tel/spy! :debug ret)
    (get ret :valid? false)))

(defn message-filter-matching-self-identifier [publication-message]
  (let [message-identifier
        (get-in publication-message [:headers :x-np-id])

        publication-identifier
        (get-in publication-message [:body :publication/self-identifier])

        subject-identifiers
        (set (mapv
              #(-> % :relation/subject-pair first)
              (get-in publication-message [:body :publication/relations])))

        all-identifiers
        (conj subject-identifiers
              message-identifier
              publication-identifier)]
    (= 1 (count all-identifiers))))

(defn message-invalidation [publication-message]
  (when
   (and (:message-filter:valid-publication-message? publication-message)
        (:message-filter:valid-signature? publication-message)
        (:message-filter:matching-self-identifier? publication-message))
   {:identifier
    (get-in publication-message [:body :publication/self-identifier])

    :invalidate-until
    (get-in publication-message [:body :publication/invalidates-previous-publications-until])}))

(defn message-invalidated? [publication-message invalidation]
  (let [{:keys [identifier invalidate-until]} invalidation]
    (and (= identifier (get-in publication-message [:body :publication/self-identifier]))
         (.isBefore
          ^java.time.Instant (get-in publication-message [:body :publication/valid-from])
          ^java.time.Instant invalidate-until))))

(defn message-filter-invalidations [invalidations]
  (fn [publication-message]
    (not-any? (partial message-invalidated? publication-message)
              invalidations)))

(defn message-filter-self
  "Filter out messages from yourself"
  [_self-identifiers]
  (fn [publication-message]
    ;; TODO: Filter 
    ;; Detect keywords in pairs...
    ;; Should be in spec...
    publication-message))

(defn independent-tests [now-instant]
  (let [and-tests
        ;; Like every-pred, but returns nil if all preds do not run...
        (fn and-tests
          ([p1 p2]
           (fn [x]
             (when (p1 x)
               (p2 x))))
          ([p1 p2 & more]
           (fn [x]
             (when (p1 x)
               (apply and-tests p2 more)))))]

    {:message-filter:valid-publication-message?
     #'message-filter-valid-publication-message

     :message-filter:valid-signature?
     (and-tests #'message-filter-valid-publication-message
                #'message-filter-valid-signature)

     :message-filter:matching-self-identifier?
     (and-tests #'message-filter-valid-publication-message
                #'message-filter-matching-self-identifier)

     :message-filter:valid-date?
     (and-tests #'message-filter-valid-publication-message
                (message-filter-valid-date now-instant))}))

(defn apply-tests [publication-message tests]
  (reduce (fn [message [k p]]
            (assoc-in message [:headers k] (p message)))
          publication-message
          tests))

;; TODO: Better naming for all this...
(defn flag-messages [now-instant publication-messages]
  (let [indep-tests
        (independent-tests now-instant)

        tested-messages
        (mapv #(apply-tests % indep-tests)
              publication-messages)

        ;; NOTE: To generate validations we need tested messages to ensure e.g. signatures.
        invalidations
        (into []
              (comp
               (map message-invalidation)
               (filter identity))
              tested-messages)

        invalidation-tested-messages
        (mapv
         (fn [publication-message]
           (as-> publication-message $
             (assoc-in $ [:headers ::message-invalidated-by]
                       (filterv (partial message-invalidated? publication-message)
                                invalidations))
             (assoc-in $ [:headers :message-filter:not-invalidated?]
                       (empty? (get-in $ [:headers ::message-invalidated-by])))))
         tested-messages)

        ;; NOTE: All filters are expected to be true for a full pass
        all-flags
        (-> #{}
            (into (keys indep-tests))
            (conj :message-filter:not-invalidated?))

        final-tested-messages
        (mapv
         (fn [publication-message]
           (assoc-in
            publication-message
            [:headers ::message-filters-all-pass?]
            (reduce (fn [acc k]
                      (and acc (get-in publication-message [:headers k])))
                    true
                    all-flags)))
         invalidation-tested-messages)]

    final-tested-messages))

(defn passing-message? [publication-message]
  (get-in publication-message [:headers ::message-filters-all-pass?]))
