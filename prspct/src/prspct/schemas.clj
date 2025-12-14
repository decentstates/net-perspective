(ns prspct.schemas
  (:require
    [clojure.string :as str]
    [clojure.set :as set]
    [clojure.math :as math]
    [clojure.pprint :refer [pprint]]

    [cognitect.anomalies :as anom]

    [babashka.fs :as fs]

    [taoensso.telemere :as tel]
    [taoensso.truss :refer [have have! have!? have? ex-info!]]

    [edamame.core :as edamame]

    [malli.core :as m]
    [malli.experimental.time :as met]
    [malli.experimental.time.generator]
    [malli.experimental.time.json-schema]
    [malli.experimental.time.transform :as mett]
    [malli.generator :as mg]
    [malli.registry :as mr]
    [malli.transform :as mt])
  (:import
    [java.time OffsetDateTime ZoneOffset]))


;; ## Schema system setup

(defn schema-setup []
  ;; TODO: Schema registry for named fields
  (mr/set-default-registry!
    (mr/composite-registry
      (m/default-schemas)
      (mr/var-registry)
      (met/schemas))))
(schema-setup)


;; ## Messages (aka emails)

;; https://en.wikipedia.org/wiki/ASCII#Character_set
(def non-control-ascii-except-clrf-re #"^[\x09\x20-\x7E]*$")
(def printable-ascii-except-colon-re #"^[\x21-\x39\x3B-\x7E]*$")
(def CRLF "\r\n")
(def header-kv-separator ":")

(def HeaderKey [:re printable-ascii-except-colon-re])
(def HeaderValue [:re non-control-ascii-except-clrf-re])

;; NOTE: This simplifies messages so headers can only appear once, this is not the case generally.
(def SimpleMessage
  "Normal Messages can have multiple headers with the same name, this only permits one header of each name."
  [:and
    [:map
     [:headers [:map-of 
                #'HeaderKey
                #'HeaderValue]]
     [:body :string]]
    [:fn (fn [m] 
           (every? (fn [[k v]]
                     (<= (+ (count k) (count header-kv-separator) (count v))
                         998)) 
                   (:headers m)))]])


(defn- unparse-simple-message-headers [headers]
  (str/join CRLF
    (for [[h v] headers]
      (str h header-kv-separator v))))

(defn- parse-simple-message-headers [raw-msg-headers]
  (if (empty? raw-msg-headers)
    {}
    (into {}
      (map (fn [header-row] 
             (let [[k v]
                   (str/split header-row (re-pattern header-kv-separator) 2)]
               [k (or v "")])))
      (str/split raw-msg-headers (re-pattern CRLF)))))


;; NOTE: eml means a raw email string
(m/=> simple-message->eml [:=> [:cat #'SimpleMessage] :string])
(defn simple-message->eml [msg]
  (let [{:keys [headers body]} msg]
    (str (unparse-simple-message-headers headers)
         CRLF CRLF
         body)))

(m/=> eml->simple-message [:=> [:cat :string] #'SimpleMessage])
(defn eml->simple-message [raw-msg]
  (let [[raw-headers raw-body] 
        (str/split raw-msg (re-pattern (str CRLF CRLF)) 2)

        raw-body
        (or raw-body "")

        parsed-message
        {:headers (parse-simple-message-headers raw-headers)
         :body raw-body}]
    (have! (m/validator SimpleMessage) parsed-message)))

(comment 
  (eml->simple-message "0")
  (let [example
        (str
          "asdf:abdf" CRLF 
          "hello:world" CRLF 
          "etc:asdf" CRLF CRLF
          "body test")]
    (eml->simple-message example)))


;; ### Internet Message Format
;; https://www.rfc-editor.org/rfc/rfc2822

(def message-date-field-pattern "EEE, dd MMM yyyy HH:mm:ss Z")
;; TODO:
(def InternetAddress "Spec for from field" :string)


;; ### EDN Messages

(defn edn-message-schema 
  ([header-schema body-schema]
   (edn-message-schema header-schema body-schema []))
  ([header-schema body-schema whole-message-schema]
   (vec
     (concat
       [:and
        {:email/header-schema header-schema
         :email/body-schema body-schema}
        [:map 
         [:headers header-schema]
         [:body body-schema]]]
       whole-message-schema))))

(def ExampleMessageHeaders
  [:map
   [:hello :string]
   [:world :string]])

(def ExampleMessageBody
  [:map
   [:pressure :int]
   [:temperature :int]
   [:volume :int]])

(def ExampleMessage
  (edn-message-schema #'ExampleMessageHeaders #'ExampleMessageBody))


(def AnyMessageHeaders
  [:map])

(def AnyMessageBody
  [:any])

(def AnyMessage
  (edn-message-schema #'AnyMessageHeaders #'AnyMessageBody))


(def edn-message<->simple-message-header-transformer
  (mt/transformer 
    (mett/time-transformer)
    (mt/key-transformer {:encode name :decode keyword})
    mt/string-transformer))


(defn encode-edn-message-body [msg-schema body]
  (let [;; TODO: I think this is a bug in malli:
        msg-schema' (if (var? msg-schema) (deref msg-schema) msg-schema)
        body-schema (get (m/properties msg-schema') :email/body-schema)]
     (with-out-str
       (pprint 
         (m/encode body-schema 
                   body
                   (mett/time-transformer))))))


(defn edn-message->simple-message [msg-schema msg]
  (let [;; TODO: I think this is a bug in malli:
        msg-schema' (if (var? msg-schema) (deref msg-schema) msg-schema)
        header-schema (get (m/properties msg-schema') :email/header-schema)]
    {:headers 
     (m/encode header-schema 
               (:headers msg) 
               edn-message<->simple-message-header-transformer)
     :body
     (encode-edn-message-body msg-schema (:body msg))}))


(defn simple-message->edn-message [msg-schema simple-message]
  (let [;; TODO: See same todo above:
        msg-schema' (if (var? msg-schema) (deref msg-schema) msg-schema)
        header-schema (get (m/properties msg-schema') :email/header-schema)
        body-schema (get (m/properties msg-schema') :email/body-schema)]
    {:headers
     (m/decode header-schema
               (:headers simple-message)
               edn-message<->simple-message-header-transformer)
     :body
     (m/coerce 
       body-schema 
       (edamame/parse-string (:body simple-message))
       (mett/time-transformer))}))


(defn edn-message->eml [msg-schema msg]
  (simple-message->eml
      (edn-message->simple-message msg-schema msg)))

(defn eml->edn-message [msg-schema eml]
  (simple-message->edn-message 
    msg-schema 
    (eml->simple-message eml)))

(comment
  (edn-message->simple-message #'ExampleMessage (mg/generate #'ExampleMessage)))



;; ### PrspctServer

(def PrspctServerUrl
  [:re #"prspct\+sftp://.*/server-info\.edn"])

(def PrspctServerInfo
  [:map
   [:version :string]
   [:publish-url :string]
   [:fetch-url :string]])


;; ### Message Transfer

(def example-publications-dir "/tmp/example-prspct/srv/np-publications")

(def MessageSourceConfigShell
  [:map
   [:shell/args [:vector [:or :string 
                              [:= :output-dir]]]]])

(def MessageSourceConfigPrspctSftp
  [:map
   [:prspct-sftp/url 
    [:re #"prspct\+sftp:.*/server-info\.edn"]]])

(defn message-source-config-dispatch [message-source-config]
  (let [namespaces (mapv namespace (keys message-source-config))]
    (when (= 1 (count namespaces))
      (case (first namespaces)
        "shell"
        :shell

        "prspct-sftp"
        :prspct-sftp))))

(def MessageSourceConfig
  [:multi {:dispatch message-source-config-dispatch}
   [:shell #'MessageSourceConfigShell]
   [:prspct-sftp #'MessageSourceConfigPrspctSftp]])

(def example-source-config
  {:shell/args ["find" example-publications-dir "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]})


(def MessagePublisherConfigShell
  [:map
   [:shell/args [:vector [:or :string 
                              [:= :input-dir] 
                              [:= :input-dir-slash-dot]]]]])

(def MessagePublisherConfigPrspctSftp
  [:map
   [:prspct-sftp/url 
    [:re #"prspct\+sftp:.*/server-info\.edn"]]])

(defn message-publisher-config-dispatch [message-publisher-config]
  (let [namespaces (mapv namespace (keys message-publisher-config))]
    (if (= 1 (count namespaces))
      (case (first namespaces)
        "shell"
        :shell

        "prspct-sftp"
        :prspct-sftp)
      ; else
      :error-multiple-namespaces)))
      
(def MessagePublisherConfig
  [:multi {:dispatch message-publisher-config-dispatch}
   [:shell #'MessagePublisherConfigShell]
   [:prspct-sftp #'MessagePublisherConfigPrspctSftp]])

(def example-publisher-config
  {:shell/args ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" example-publications-dir ";"]})

(def MessageTransferResult
  [:map
   [:transfer-result/success? :boolean]
   [:transfer-result/exception :any]
   [:transfer-result/out :string]
   [:transfer-result/err :string]])


(def EDNMessageEnvelope
  [:map
   {:gen/schema #'ExampleMessage
    :gen/fmap (fn [message]
                {:publisher example-publisher-config
                 :message-schema #'ExampleMessage
                 :message message})}
   [:publisher #'MessagePublisherConfig]
   [:message-schema :any]
   [:message :any]])


;; #### Message Transfer FetchInfo

(def FetchInfoSource
  [:map
   [:fetch-info-source/output-dir :string]
   [:fetch-info-source/status [:enum :not-started :running :finished]]
   [:fetch-info-source/time-start [:time/instant]]
   [:fetch-info-source/time-finish [:time/instant]]
   [:fetch-info-source/transfer-result #'MessageTransferResult]
   [:fetch-info-source/success? :boolean]])

(def FetchInfo 
  [:map
   [:fetch-info/uuid :uuid]
   [:fetch-info/time-start [:time/instant]]
   [:fetch-info/time-finish [:time/instant]]
   [:fetch-info/sources [:map-of #'MessageSourceConfig #'FetchInfoSource]]])

(defn encode-fetch-info [fetch-info]
  (with-out-str
    (pprint
      (m/encode #'FetchInfo 
                fetch-info 
                (mett/time-transformer)))))

(defn decode-fetch-info [s]
   (m/decode #'FetchInfo
             (edamame/parse-string s)
             (mett/time-transformer)))


;; #### Message Transfer FetchInfo

(def PublishInfoPublisher
  [:map
   [:publish-info-source/input-dir :string]
   [:publish-info-source/status [:enum :not-started :running :finished]]
   [:publish-info-source/time-start [:time/instant]]
   [:publish-info-source/time-finish [:time/instant]]
   [:publish-info-source/transfer-result #'MessageTransferResult]
   [:publish-info-source/success? :boolean]])

(def PublishInfo
  [:map
   [:publish-info/uuid :uuid]
   [:publish-info/time-start [:time/instant]]
   [:publish-info/time-finish [:time/instant]]
   [:publish-info/publishers [:map-of #'MessagePublisherConfig #'PublishInfoPublisher]]])


(defn encode-publish-info [publish-info]
  (with-out-str
    (pprint
      (m/encode #'PublishInfo 
                publish-info 
                (mett/time-transformer)))))

(defn decode-publish-info [s]
   (m/decode #'PublishInfo
             (edamame/parse-string s)
             (mett/time-transformer)))


;; ## net-perspective:

;; ### Identifiers:

(def IdentifierEmail
  [:re 
   {:gen/schema [:int {:min 0 :max 9999}]
    :gen/fmap (fn [i] (str "email:u-" (format "%04d" i) "@example.com"))}
   #"^email:.*@.*$"])

(defn identifier-email? [ident]
  (and (string? ident)
       (str/starts-with? ident "email:")))

(defn identifier-email->email [ident]
  (have! identifier-email? ident)
  (str/replace-first ident #"^email:" ""))

(def IdentifierSSHKey
  [:schema
   {:gen/schema [:int {:min 0 :max 10}]
    :gen/fmap (fn [i] (str "ssh-key:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5+invalid+key+" i "+="))}
   [:or
    [:re #"^ssh-key:ssh-dss AAAAB3NzaC1kc3[0-9A-Za-z+/]+[=]{0,3}$"]
    [:re #"^ssh-key:ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNT[0-9A-Za-z+/]+[=]{0,3}$"]
    [:re #"^ssh-key:ecdsa-sha2-nistp384 AAAAE2VjZHNhLXNoYTItbmlzdHAzOD[0-9A-Za-z+/]+[=]{0,3}$"]
    [:re #"^ssh-key:ecdsa-sha2-nistp521 AAAAE2VjZHNhLXNoYTItbmlzdHA1Mj[0-9A-Za-z+/]+[=]{0,3}$"]
    [:re #"^ssh-key:sk-ecdsa-sha2-nistp256@openssh.com AAAAInNrLWVjZHNhLXNoYTItbmlzdHAyNTZAb3BlbnNzaC5jb2[0-9A-Za-z+/]+[=]{0,3}$"]
    [:re #"^ssh-key:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5[0-9A-Za-z+/]+[=]{0,3}$"]
    [:re #"^ssh-key:sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29t[0-9A-Za-z+/]+[=]{0,3}$"]
    [:re #"^ssh-key:ssh-rsa AAAAB3NzaC1yc2[0-9A-Za-z+/]+[=]{0,3}$"]]])

(defn ssh-public-key->identifier-ssh [s]
  (m/coerce #'IdentifierSSHKey
    (str "ssh-key:"
         ;; We have to strip the comment off the ssh key
         (re-find #"[a-z0-9\.@\-]+ [0-9A-Za-z+/]+[=]{0,3}" s))))

(defn identifier-ssh-key? [ident]
  (and (string? ident)
       (str/starts-with? ident "ssh-key:")))

(defn identifier-ssh-key->ssh-key [ident]
  (have! identifier-ssh-key? ident)
  (str/replace-first ident #"^ssh-key:" ""))

(def IdentifierURI
  [:re 
   {:gen/schema [:int {:min 0 :max 10}]
    :gen/fmap (fn [i] (str "uri:http://example.com/" i))}
   #"^uri:.+$"])

(defn identifier-uri? [ident]
  (and (string? ident)
       (str/starts-with? ident "uri:")))

(defn identifier-uri->uri [ident]
  (have! identifier-uri? ident)
  (str/replace-first ident #"^uri:" ""))


(defn identifier-dispatch [ident]
  (cond
    (string? ident)
    (first (str/split ident #":" 2))

    :else
    :unknown))

(defn identifier-value [ident]
  (cond
    (string? ident)
    (second (str/split ident #":" 2))

    :else
    :unknown))

(def Identifier
  [:multi {:dispatch identifier-dispatch}
    ["email"   #'IdentifierEmail]
    ["ssh-key" #'IdentifierSSHKey]
    ["uri"     #'IdentifierURI]])


;; ### Contexts:

;; NOTE: Fewer example-contexts means in generation we will have higher levels of connection
(def example-contexts 
  ["#comp.ai"
   "#comp.ai.bayesian"
   "#comp.ai.expert-systems"
   "#comp.ai.llm"
   "#comp.ai.machine-learning"])

(def example-contexts-extended
  (concat
    example-contexts
    ["#comp.lang.asm.x86"
     "#comp.lang.c"
     "#comp.lang.cpp"
     "#comp.lang.java"
     "#comp.lang.javascript"
     "#comp.lang.perl"
     "#comp.lang.python"
     "#comp.theory"
     "#comp.theory.cell-automata"
     "#comp.theory.self-org-sys"
     "#comp.theory.dynamic-sys"
     "#comp.theory.info-retrieval"
     "#net-perspective.admin"
     "#net-perspective.admin.announcements"
     "#net-perspective.admin.misc"
     "#net-perspective.admin.technical"
     "#net-perspective.announcements"
     "#net-perspective.announcements.important"
     "#net-perspective.misc"
     "#sci.astro"
     "#sci.bio"
     "#sci.crypt"
     "#sci.electronics"
     "#sci.lang"
     "#sci.lang.japan"
     "#sci.logic"
     "#sci.math"
     "#sci.math.applied"
     "#sci.math.pure"
     "#sci.math.stats"
     "#sci.med"
     "#sci.med"
     "#sci.military"
     "#sci.misc"
     "#sci.nanotech"
     "#sci.philosophy.tech"
     "#sci.physics"
     "#sci.psychology"
     "#sci.research"
     "#sci.space"]))

;; TODO: Internationalisation? Maybe not
(def ContextNoMatcher 
  "E.g.: #path.to.my.context"
  [:re 
   {:gen/elements example-contexts} 
   #"^#(?:[a-z][a-z0-9\-]*\.?)*$"])

(def example-context-matchers
  ["#comp.*"
   "#comp.ai.*"
   "#comp.lang.*"
   "#comp.os.*"
   "#comp.theory.*"])
   
(def ContextMatcher
  "E.g.: #path.to.my.context.*"
  [:re 
   {:gen/elements example-context-matchers} 
   #"^#(?:[a-z][a-z0-9\-]*\.?)*\*\*?$"])

(def Context
  [:or
   #'ContextNoMatcher
   #'ContextMatcher])

(def example-internal-context-parts
  ["comp"
   "ai" 
   "llm" 
   "green" 
   "blue"])

(def InternalContextPart
  [:or
    [:re 
     {:gen/elements example-internal-context-parts} 
     #"^[a-z][a-z0-9\-]*$"]
    [:= "*"]
    [:= "**"]])

(def example-internal-contexts
  [["comp" "ai"]
   ["comp" "ai" "bayesian"]
   ["comp" "ai" "expert-systems"]
   ["comp" "ai" "llm"]
   ["comp" "ai" "machine-learning"]])

(def InternalContext
  [:vector 
   {:gen/elements example-internal-contexts}
   #'InternalContextPart])

(m/=> context->internal-context [:=> [:cat #'Context] #'InternalContext])
(defn context->internal-context [ctx]
  (have! (= ctx (m/coerce #'Context ctx)))
  (let [ctx (m/coerce #'Context ctx)
        splits (str/split (subs ctx 1) #"\." -1)]
    (if (= splits [""])
      []
      splits)))

(m/=> internal-context->context [:=> [:cat #'InternalContext] #'Context])
(defn internal-context->context [parsed-ctx]
  (str "#" (str/join "." (m/coerce #'InternalContext parsed-ctx))))

(def example-internal-contexts-extended
  (mapv context->internal-context example-contexts-extended))


;; ### Relations:

(def SubjectPair
  [:tuple #'Identifier #'InternalContext])

(def ObjectPair
  [:tuple #'Identifier #'InternalContext])

(def Relation 
  [:map
     [:relation/subject-pair #'SubjectPair]
     [:relation/object-pair #'ObjectPair]
     [:relation/transitive? :boolean]
     [:relation/public? :boolean]])

(defn- int->bit-vector [n]
  (mapv #(Character/digit ^char % 2) 
        (.toString (.toBigInteger (bigint n)) 2)))

(defn- bit-vector->binary-square-matrix 
  "Pads from the right"
  [bit-vector order]
  (let [o order]
    (first
      (partition o o (repeat (repeat o 0))
          (partition o o (repeat 0) bit-vector)))))

(defn- adjacency-matrix-to-relations [am sub-pairs transitive? public?]
  (have! (= (count am) (count sub-pairs)))
  (into []
      (comp 
        (map-indexed
         (fn [i row]
             (map-indexed 
               (fn [j bit]
                 [i j bit])
               row)))
        cat
        (filter (fn [[i j _b]] (not= i j)))
        (filter (fn [[_i _j b]] (= b 1)))
        (map 
          (fn [[i j _b]] 
            {:relation/subject-pair (nth sub-pairs j)
             :relation/object-pair (nth sub-pairs i)
             :relation/transitive? transitive?
             :relation/public? public?})))
      am))

(defn- sub-idx-relations [relations]
  (group-by :relation/subject-pair relations))

(defn- gen-subject-pair->relations [n-transitive n-non-transitive sub-pairs matrix-order]
    (let [sub-pairs         (vec sub-pairs)
          am-transitive     (bit-vector->binary-square-matrix (int->bit-vector n-transitive) matrix-order)
          am-non-transitive (bit-vector->binary-square-matrix (int->bit-vector n-non-transitive) matrix-order)]
      (sub-idx-relations
        (concat
          (adjacency-matrix-to-relations am-transitive sub-pairs true false)
          (adjacency-matrix-to-relations am-non-transitive sub-pairs false false)))))

;; TODO: Replace with generator: vector of SubjectPairs -> bind, using order to create random bytes
;; TODO: Add globbing in
(def ^:private gen-adjacency-matrix-order 8)
(def SubjectPair->Relations
  [:map-of
   {:gen/schema 
    [:tuple [:int {:min 0 :gen/size (math/pow gen-adjacency-matrix-order 2)}]
            [:int {:min 0 :gen/size (math/pow gen-adjacency-matrix-order 2)}]
            [:set 
             {:min gen-adjacency-matrix-order
              :max gen-adjacency-matrix-order}
             SubjectPair]]
    :gen/fmap (fn [[n-transitive n-non-transitive sub-pairs]]
                (gen-subject-pair->relations n-transitive n-non-transitive sub-pairs gen-adjacency-matrix-order))} 
   #'SubjectPair
   [:vector #'Relation]])


;; ### Signing

(def EncodedPublicationSignature :string)

(def PublicationSignature
  [:map
   [:version :string]
   [:id #'IdentifierSSHKey]
   [:hash-algorithm [:enum "sha256"]]
   [:hash :string]
   [:signature-method [:enum "ssh-keygen-sign-whole-message"]]
   [:signature :string]])

(def publication-signature-transformer
  (mt/transformer 
    (mett/time-transformer)
    (mt/key-transformer {:encode name :decode keyword})
    mt/string-transformer))

(defn encode-publication-signature [publication-signature]
  (pr-str
    (m/encode
      #'PublicationSignature
      publication-signature
      publication-signature-transformer)))

(defn decode-publication-signature [encoded-publication-signature]
  (m/decode
    #'PublicationSignature
    (edamame/parse-string encoded-publication-signature)
    publication-signature-transformer))


;; ### Publications
;; A publication is the central API contract we expose.

;; NOTE: The central contract we expose.
(def Publication
  ;; TODO: Constraints
  [:map 
   [:publication/version [:enum :alpha-do-not-spread]]
   [:publication/valid-from [:time/instant]]
   [:publication/valid-until [:time/instant]]
   [:publication/invalidates-previous-publications-until [:time/instant]]
   [:publication/self-identifier #'Identifier]
   [:publication/relations [:vector #'Relation]]])

;; REVIEW: What should be here and what should be in publication, should this exist.
(def PublicationMessageHeaders
  [:map 
   [:from :string]
   [:subject :string]
   [:date [:time/offset-date-time 
           {:pattern 
            message-date-field-pattern
            ;; NOTE: The malli generator for offset datetimes produces offsets
            ;; that are too precise for our use.
            :gen/elements 
            [(OffsetDateTime/of 2025 9 23 8 1 1 0 (ZoneOffset/ofHours -3))
             (OffsetDateTime/of 2025 9 23 3 1 1 0 (ZoneOffset/ofHours 3))
             (OffsetDateTime/of 2025 9 23 3 1 1 0 (ZoneOffset/ofHours 2))]}]]
   [:x-np [:= "net-perspective.org"]]
   [:x-np-client [:re non-control-ascii-except-clrf-re]]
   [:x-np-id #'Identifier]
   [:x-np-timestamp :time/instant]
   [:x-np-intent [:enum :publication]]
   [:x-np-signature {:optional true} #'EncodedPublicationSignature]])

(def PublicationMessage
  (edn-message-schema #'PublicationMessageHeaders #'Publication))


;; ## User Configuration
;; REVIEW: Split this into stricter WorkingConfiguration, for post
;;         transformation and mix.

;; REVIEW: Really should use internal/external relations, with internal allowing keywords.
(def UserConfigIdentifier
  [:or #'Identifier
       :keyword
       :qualified-keyword])

(defn include-ident? [ident]
  (and (qualified-keyword? ident)
       (= "<" (namespace ident))))

(defn include-ident->internal-context [ident]
  (have include-ident? ident)
  (str/split (name ident) #"\." -1))

(defn internal-context->include-ident [internal-context]
  (have! not-empty internal-context)
  (keyword "<" (str/join "." internal-context)))

(defn include-ident-rel? [rel]
  (-> rel :relation/object-pair first include-ident?))

(defn include-ident-rel->internal-context [rel]
  (-> rel :relation/object-pair first include-ident->internal-context))

(def FilePath 
  [:string
   {::file-path true}])

(defn file-path-transformer [path-relative-to]
  (mt/transformer
    {:default-decoder
     {:compile
      (fn [schema _]
        (when (get (m/properties schema) ::file-path)
          (fn [f]
            (as-> f $
              (fs/expand-home $)
              (if (fs/relative? $)
                (fs/path path-relative-to $)
                $)
              (str $)))))}}))

(def PublishToName :string)
(def PublishToEmail [:re #"^.*@.*$"]) 

(def PublishIdentity
  [:map
   [:name #'PublishToName]
   [:email #'PublishToEmail]
   [:ssh-key-id/public-key-path #'FilePath]
   [:ssh-key-id/private-key-path #'FilePath]])

(def UserObjectPair
  [:tuple #'UserConfigIdentifier #'InternalContext])

(def UserRelation
  [:map
   [:relation/object-pair #'UserObjectPair]
   [:relation/transitive? :boolean]
   [:relation/public? :boolean]])


(def example-user-config-options
  {:sources
   {}

   :publishers
   {}

   :publish-identities 
   {}

   :publish-configs 
   {}

   :default-publish-configs 
   []})
   

(def PublishConfig
  [:map
   [:publisher :keyword]
   [:identity :keyword]
   [:publication-validity-seconds {:optional true :default (* 60 60 24 7 4)} :int]]) 

(def UserConfigOptions
  [:map
   {:closed true}
   [:sources                 [:map-of :keyword #'MessageSourceConfig]]
   [:publishers              [:map-of :keyword #'MessagePublisherConfig]]
   [:publish-identities      [:map-of :keyword #'PublishIdentity]]
   [:publish-configs         [:map-of :keyword #'PublishConfig]]
   [:default-publish-configs [:vector :keyword]]])

(def UserContextConfig
  [:map
   {:closed true}
   [:publish-configs {:optional true} [:vector :keyword]]])

(def UserContext
  [:map
   [:context #'InternalContext]
   [:relations [:vector #'UserRelation]]
   [:context-config #'UserContextConfig]])

(def UserConfig 
  [:map
   [:user-config-options #'UserConfigOptions]
   [:user-contexts [:vector #'UserContext]]])


;; ### Hacky User Config DSL

(def example-user-relations-dsl
  "The idea is less syntax for easier editing but still edn."

  ;; TODO: # or #. or even empty string?
  ;; TODO: Can simplify it further, by allowing references, path searches, structured external identifiers
  ;;       a big shared data structure.
  '[(ctx "#self.contacts"
         ;; I want this to work like file paths, but also don't want
         ;; to have to have two lines... maybe a special syntax
         ;; d+ d.?* d* d->...
         (-> "email:bob@gmail.com" "#self.contacts.*" :public)

         (ctx "d"
              (->> "email:fabian@whatever" "#self" :public)
              (-> "email:fabian@2" "#self" :public))
         (ctx "d.*"
              (-> "email:asdf@asdf" "#self.*" :public)))
    (ctx "#underties" {:np.app.underties-server.nix-config-output-path "server.nix"
                       :np.app.underties-server.nixos-deploy "hostname"}
         (-> :</self.contacts.d "#underties" :public)
         (-> :</self.contacts.f "#underties" :public))
    (ctx "#comp.sys.nix"
         (->> :</self.contacts.d "#comp.sys.nix" :public)
         (->>  "email:asdfd@fsdf" "#nix" :public)
         (->  "email:as@sdfasdfdf" "#nixos" :public)
         (->  "uri:http://asdf.com" "#null" :public))])

(def UserRelationsDSLContextPart 
  [:re #"^#?(?:[a-z][a-z0-9\-]*\.?)*\*?\*?$"])

(def UserRelationsDSLRelationEntry
  [:schema
   [:cat
    [:or [:= '->] [:= '->>]]
    #'UserConfigIdentifier
    [:? #'Context]
    [:? [:= :public]]]])

(def UserRelationsDSLContextEntry
  [:schema 
   {:registry 
    {::relation-dsl
     #'UserRelationsDSLRelationEntry

     ::context-dsl 
     [:schema
       ;; NOTE: Using a multi gives much better error messages
       [:multi {:dispatch (fn [x] (map? (nth x 2 nil)))}
        [true
         [:cat 
          [:= 'ctx]
          #'UserRelationsDSLContextPart
          #'UserContextConfig
          [:* [:multi {:dispatch first}
               ['-> [:ref ::relation-dsl]]
               ['->> [:ref ::relation-dsl]]
               ['ctx [:ref ::context-dsl]]]]]]
        [false
         [:cat 
          [:= 'ctx]
          #'UserRelationsDSLContextPart
          [:* [:multi {:dispatch first}
               ['-> [:ref ::relation-dsl]]
               ['->> [:ref ::relation-dsl]]
               ['ctx [:ref ::context-dsl]]]]]]]]}}
   [:ref ::context-dsl]])

(def UserRelationsDSL
  [:cat 
   [:* [:alt [:and #'UserRelationsDSLContextEntry
                   [:cat 
                    [:= 'ctx]
                    [:re
                     {:doc "Root contexts must start with a hash"}
                     #"^#.*$"]
                    [:* :any]]]]]])


(defn parse-user-relations-dsl-relation 
  ([transitivity object-identifier]
   (parse-user-relations-dsl-relation transitivity object-identifier "#" false))

  ([transitivity object-identifier x]
   (cond
     (string? x)
     (let [object-context x]
       (parse-user-relations-dsl-relation transitivity object-identifier object-context false))
      
     (= :public x)
     (parse-user-relations-dsl-relation transitivity object-identifier "#" :public)
     
     :else
     (ex-info! (str "Unknown parameter in relation"))))

  ([transitivity object-identifier object-context public?]
   {:relation/object-pair [object-identifier (context->internal-context object-context)]
    :relation/transitive? (= '->> transitivity)
    :relation/public? (= :public public?)}))

;; TODO: Pass through metadata
(defn parse-user-relations-dsl-context [[_ context-part & more] parent-context parent-config]
  (let [;; HACK: The schema coercion converts the lists into vectors, we convert them back into lists here.
        more
        (apply list more)

        [config children] 
        (if (map? (peek more)) 
          [(peek more) (pop more)]
          [{} more])

        ctx-children 
        (filterv #(contains? #{'ctx} (first %)) children)

        rel-children 
        (filterv #(contains? #{'-> '->>} (first %)) children)

        context-name (cond
                       (= :root parent-context) context-part
                       ;; TODO: Maybe should just be #. for root or "." for root
                       (= "#" parent-context) (str "#" context-part)
                       :else (str parent-context "." context-part))

        context-config (m/coerce 
                         #'UserContextConfig
                         (merge parent-config config)
                         (mt/default-value-transformer {::mt/add-optional-keys true}))

        this-ctx {:context (context->internal-context context-name)
                  :context-config context-config
                  :relations (mapv (partial apply parse-user-relations-dsl-relation) rel-children)}]
    (concat
      [this-ctx]
      (vec (mapcat #(parse-user-relations-dsl-context % context-name context-config)
                 ctx-children)))))

(defn user-relations-dsl->user-config [user-relations-dsl]
  (let [root-ctx-forms (filterv #(= 'ctx (first %)) user-relations-dsl)
        
        parsed-contexts
        (vec (mapcat #(parse-user-relations-dsl-context % :root {}) root-ctx-forms))

        user-config
        (m/coerce [:vector #'UserContext] parsed-contexts)

        contexts
        (set (mapv :context user-config))
        
        include-idents-internal-contexts
        (into #{}
              (comp
                (mapcat :relations)
                (filter include-ident-rel?)
                (map include-ident-rel->internal-context))
              user-config)

        include-idents-not-matching
        (mapv
          internal-context->include-ident
          (set/difference include-idents-internal-contexts contexts))]

    (when (not-empty include-idents-not-matching)
      (ex-info! (str "Could not find matching contexts for include identifiers:\n"
                     (with-out-str
                      (doseq [x include-idents-not-matching]
                        (println (str "    " x)))))
                {::anom/category ::anom/incorrect
                 :include-idents-not-matching include-idents-not-matching
                 :instructions 
                 (with-out-str
                   (println "Fix or remove the include identifier `:</context.name`.")
                   (println "`context.name` must match a defined `(ctx \"#context.name\" ...)`") 
                   (println "Perhaps you have a typo or renamed a context?"))}))

    user-config))

(comment
  (user-relations-dsl->user-config example-user-relations-dsl))


;; ## "Working" Configuration
;; What we work with. A stricter subset of UserConfig
;; What we process UserConfig into.
;; TODO: Rename to WorkingUserConfig

(def WorkingContextConfig
  [:map
   {:closed true}
   [:publish-configs {:optional true} [:vector :keyword]]])

(def WorkingUserRelation
  [:map
   [:relation/object-pair #'UserObjectPair]
   [:relation/transitive? :boolean]
   [:relation/public? :boolean]])

;; TODO Context vs WorkingContext vs UserContext vs UserContextConfig vs resolved-config is annoying...
(def WorkingContext
  [:map
   [:context #'InternalContext]
   [:relations [:vector #'WorkingUserRelation]]
   [:context-config #'WorkingContextConfig]])

(def WorkingContextResolution
  [:set #'WorkingUserRelation])

(def ResolvedContexts
  [:map-of #'InternalContext [:set #'UserObjectPair]])
  
(def WorkingConfig
  [:map
   [:user-config-options #'UserConfigOptions]
   [:working-contexts [:vector #'WorkingContext]]
   ;; TODO Rename to resolved-self-contexts
   [:resolved-self-contexts #'ResolvedContexts]])


