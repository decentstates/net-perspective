(ns prspct.schemas
  (:require
    [clojure.string :as str]
    [clojure.math :as math]
    [clojure.pprint :refer [pprint]]

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
    [malli.transform :as mt]
    [malli.util :as mu])
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


;; ### Message Transfer

(def example-publications-dir "/tmp/example-prspct/srv/np-publications")

(def MessageSourceConfig
  [:map
   [:source/fn :qualified-symbol]
   [:source/args vector?]])

(def example-source-config
  {:source/fn 'prspct.message-transfer/shell-source
   :source/args ["find" example-publications-dir "-name" "*.eml" "-exec" "cp" "{}" :output-dir ";"]})

(def MessagePublisherConfig
  [:map
   [:publisher/fn :qualified-symbol]
   [:publisher/args vector?]])

(def example-publisher-config
  {:publisher/fn 'prspct.message-transfer/shell-publisher
   :publisher/args ["find" :input-dir "-name" "*.eml" "-exec" "cp" "{}" example-publications-dir ";"]})

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


;; ## Net Perspective:

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
   [:signature-method [:enum "ssh-sign-whole-msg"]]
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
   [:publication/self-identifier #'Identifier]
   [:publication/relations [:vector #'Relation]]
   [:publication/signature {:optional true} #'EncodedPublicationSignature]])

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
   [:x-np-intent [:enum :publication]]])

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


(defn expand-home [s]
  (if (str/starts-with? s "~")
    (str/replace-first s "~" (System/getProperty "user.home"))
    s))

(def FilePath 
  [:string
   {:decode/file-path {:leave expand-home}}])

(def PublishToName :string)
(def PublishToEmail [:re #"^.*@.*$"]) 

(def PublishToConfig
  [:map
   [:publisher :keyword]
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


(def ContactsConfig
  [:map
   [:ctx :string]
   [:under-namespace [:or :keyword :string]]])

(def UserContextConfig
  [:map
   {:closed true}
   [:np/sources    {:optional true} [:map-of :keyword #'MessageSourceConfig]]
   [:np/publishers {:optional true} [:map-of :keyword #'MessagePublisherConfig]]
   [:np/publish-to {:optional true} [:vector #'PublishToConfig]]
   ;; TODO: Apply defaults only to "#"
   [:np/publication-validity-days {:optional true :default 30} [:int {:min 1}]]
   [:np.contacts/configs {:optional true} [:vector #'ContactsConfig]]])

(def UserContext
  [:map
   [:context #'InternalContext]
   [:relations [:vector #'UserRelation]]
   [:config #'UserContextConfig]])

(def UserConfig 
  [:map
   [:user-contexts [:vector #'UserContext]]])


;; ### Hacky User Config DSL

(def example-user-config
  "The idea is less syntax for easier editing but still edn."

  ;; TODO: # or #. or even empty string?
  ;; TODO: Can simplify it further, by allowing references, path searches, structured external identifiers
  ;;       a big shared data structure.
  '[(ctx "#"
         {:np/sources {}
          :np/publishers {} ;;ctx-config
          :np/publish-to [{:publisher :blah
                           :name  "Alice"
                           :email "alice@example.com"
                           :ssh-key-id/public-key-path "/tmp/example-key-stub"
                           :ssh-key-id/private-key-path "/tmp/example-key-stub.pub"}]
          :np.app.news/sources {}
          :np.app.news/publishers {}
          :np.app.news/publish-to {}
          :np.contacts/config [{:ctx "#self.contacts" 
                                :under-namespace :contacts}]} 
         (ctx "self" {}
              (ctx "contacts" {}
                   ;; I want this to work like file paths, but also don't want
                   ;; to have to have two lines... maybe a special syntax
                   ;; d+ d.?* d* d->...
                   (-> "email:bob@gmail.com" "#self.contacts.*" :public)
                   (ctx "d"
                        (->> "email:fabian@whatever" "#self" :public)
                        (-> "email:fabian@2" "#self" :public))
                   (ctx "d.*"
                        (-> "email:asdf@asdf" "#self.*" :public))))
         (ctx "underties" {:np.app.underties-server.nix-config-output-path "server.nix"
                                     :np.app.underties-server.nixos-deploy "hostname"}
              (-> :contacts/d "#underties" :public)
              (-> :contacts/f "#underties" :public))
         (ctx "comp.sys.nix" {}
              (->> :contacts/d "#comp.sys.nix" :public)
              (->>  "email:asdfd@fsdf" "#nix" :public)
              (->  "email:as@sdfasdfdf" "#nixos" :public)
              (->  "uri:http://asdf.com" "#null" :public)))])

(def UserConfigDSLContextPart 
  [:re #"^#?(?:[a-z][a-z0-9\-]*\.?)*\*?\*?$"])

(def UserConfigDSLRelationEntry
  [:schema
   [:cat
    [:or [:= '->] [:= '->>]]
    #'UserConfigIdentifier
    #'Context
    [:? [:= :public]]]])

(def UserConfigDSLContextEntry
  [:schema 
   {:registry 
    {::relation-dsl
     #'UserConfigDSLRelationEntry

     ::context-dsl 
     [:schema
       ;; NOTE: Using a multi gives much better error messages
       [:multi {:dispatch (fn [x] (map? (nth x 2 nil)))}
        [true
         [:cat 
          [:= 'ctx]
          #'UserConfigDSLContextPart
          #'UserContextConfig
          [:* [:multi {:dispatch first}
               ['-> [:ref ::relation-dsl]]
               ['->> [:ref ::relation-dsl]]
               ['ctx [:ref ::context-dsl]]]]]]
        [false
         [:cat 
          [:= 'ctx]
          #'UserConfigDSLContextPart
          [:* [:multi {:dispatch first}
               ['-> [:ref ::relation-dsl]]
               ['->> [:ref ::relation-dsl]]
               ['ctx [:ref ::context-dsl]]]]]]]]}}
   [:ref ::context-dsl]])

(def UserConfigDSL
  [:cat 
   [:* [:alt [:and #'UserConfigDSLContextEntry
                   [:cat 
                    [:= 'ctx]
                    [:re
                     {:doc "Root contexts must start with a hash"}
                     #"^#.*$"]
                    [:* :any]]]]]])

(defn parse-user-config-dsl-relation 
  ([transitivity object-identifier object-context]
   (parse-user-config-dsl-relation transitivity object-identifier object-context :public))

  ([transitivity object-identifier object-context public?]
   {:relation/object-pair [object-identifier (context->internal-context object-context)]
    :relation/transitive? (= '->> transitivity)
    :relation/public? (= :public public?)}))

(defn parse-user-config-dsl-context [[_ context-part & more] parent-context parent-config]
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

        ;; TODO: Apply defaults
        context-config (merge parent-config config)

        this-ctx {:context (context->internal-context context-name)
                  :config context-config
                  :relations (mapv (partial apply parse-user-config-dsl-relation) rel-children)}]
    (concat
      [this-ctx]
      (vec (mapcat #(parse-user-config-dsl-context % context-name context-config)
                 ctx-children)))))

(defn user-config-dsl->user-config [user-config-dsl]
  (let [root-ctx-forms (filterv #(= 'ctx (first %)) user-config-dsl)
        
        parsed-contexts
        (vec (mapcat #(parse-user-config-dsl-context % :root {}) root-ctx-forms))]
    (m/coerce #'UserConfig {:user-contexts parsed-contexts})))

(comment
  (user-config-dsl->user-config example-user-config))


;; ## "Working" Configuration
;; What we work with. A stricter subset of UserConfig
;; What we process UserConfig into.

(def WorkingContextConfig
  [:map
   {:closed true}
   [:np/sources    {:optional true} [:map-of :keyword #'MessageSourceConfig]]
   [:np/publishers {:optional true} [:map-of :keyword #'MessagePublisherConfig]]
   [:np/publish-to {:optional true} [:vector #'PublishToConfig]]
   [:np/publication-validity-days {:optional true :default 30} [:int {:min 1}]]
   [:np.contacts/configs {:optional true} [:vector #'ContactsConfig]]])

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
   [:config #'WorkingContextConfig]])

(def WorkingContextResolution
  [:set #'WorkingUserRelation])

(def ResolvedContexts
  [:map-of #'InternalContext [:set #'UserObjectPair]])
  
(def WorkingConfig
  [:map
   [:working-contexts [:vector #'WorkingContext]]
   [:resolved-contexts #'ResolvedContexts]])


