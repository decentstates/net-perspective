(ns prspct.resolution
  [:require 
   [clojure.string :as str]

   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [malli.core :as m]

   [prspct.relation-graph :as rel-graph]
   [prspct.publication]
   [prspct.schemas :as ps]])


;; ## Utils

(defn ctx-child? [parent child]
    (and (= (inc (count parent)) (count child))
         (= parent (subvec child 0 (count parent)))))

(defn ctx-match? [matcher ctx]
  (let [last-part (last matcher)
        before-last-part (vec (butlast matcher))]
    (case last-part
      "*"
      (and (= (inc (count before-last-part)) (count ctx))
           (= before-last-part (subvec ctx 0 (count before-last-part))))

      "**" 
      (= before-last-part (subvec ctx 0 (count before-last-part)))

      (= matcher ctx))))

(comment
  (= (ctx-match? ["a"] ["a"]) 
     true)
  (= (ctx-match? ["a"] ["a" "b"])
     false)
  (= (ctx-match? ["a" "*"] ["a" "b"])
     true)
  (= (ctx-match? ["a" "*"] ["a" "b" "c"])
     false)
  (= (ctx-match? ["a" "**"] ["a" "b"])
     true)
  (= (ctx-match? ["a" "**"] ["a" "b" "c"])
     true)
  (= (ctx-match? ["b" "**"] ["a" "b" "c"])
     false))

;; ## Publication Message filters
;; TODO: test

(defn message-filter-valid-publication-message [publication-message]
  (get-in publication-message [:headers :prspct.message-transfer/publication-message?]))

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
  (let [publication (:body publication-message)
        ret (prspct.publication/verify-publication publication)]
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
  {:identifier
   (get-in publication-message [:body :publication/self-identifier])
   
   :invalidate-until
   (get-in publication-message [:body :publication/invalidates-previous-publications-until])})

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
  [self-identifiers]
  (fn [publication-message]
    ;; TODO: Filter 
    ;; Detect keywords in pairs...
    ;; Should be in spec...
    publication-message))


;; Resolution

(m/=> resolve-contexts [:=> [:cat [:vector #'ps/UserContext] [:vector #'ps/Relation]] 
                            #'ps/ResolvedContexts])
(defn resolve-contexts [user-contexts fetched-rels]
  (tel/event! ::resolve-contexts:start)
  (let [initial-self-rels 
        (into [] 
             (mapcat (fn [{:keys [context relations]}]
                       (mapv #(assoc % :relation/subject-pair [:self context])
                             relations)))
             user-contexts)

        glob-rels 
        (-> (concat fetched-rels initial-self-rels)
            rel-graph/relations->rel-graph
            rel-graph/resolve-graph-globs
            rel-graph/rel-graph->relations)

        _ (tel/event! ::resolve-contexts:calculated-glob-rels)

        all-rels 
        (concat fetched-rels initial-self-rels glob-rels)

        g
        (rel-graph/relations->rel-graph all-rels)

        _ (tel/event! ::resolve-contexts:generated-graph)

        resolved-self-rels
        (filterv (fn [rel] (= (first (:relation/subject-pair rel))
                              :self))
                all-rels)

        resolved-contexts
        (into {}
             (map (fn [{:relation/keys [subject-pair]}]
                    (let [[_ ctx] subject-pair]
                      [ctx (rel-graph/rnode-reach subject-pair g)])))
             resolved-self-rels)

        _ (tel/event! ::resolve-contexts:resolved-contexts)]
    resolved-contexts))

;; TODO: Move to schemas
(defn is-include-ident? [ident]
  (and (qualified-keyword? ident)
       (= "<" (namespace ident))))

(defn include-ident->internal-context [ident]
  (have is-include-ident? ident)
  (str/split (name ident) #"\." -1))

(defn is-include-ident-rel? [rel]
  (-> rel :relation/object-pair first is-include-ident?))

(defn include-ident-rel->internal-context [rel]
  (-> rel :relation/object-pair first include-ident->internal-context))

(defn resolve-include-ident-rel [resolved-contexts rel]
  (let [context-to-include
        (include-ident-rel->internal-context rel)

        identities-to-include
        (mapv first (get resolved-contexts context-to-include))

        obj-context
        (second (:relation/object-pair rel))]
    (mapv 
      #(assoc rel :relation/object-pair [% obj-context])
      identities-to-include)))

(defn user-context-with-include-rels [user-contexts resolved-contexts]
  (mapv
    (fn [user-context]
       (update user-context :relations 
               (fn [relations]
                 (let [include-relations 
                       (filterv is-include-ident-rel? relations)
                       
                       includes
                       (mapv (partial resolve-include-ident-rel resolved-contexts) include-relations)]
                   (vec (distinct (apply concat relations includes)))))))
    user-contexts))

(m/=> resolve-contexts-with-includes [:=> [:cat [:vector #'ps/UserContext] [:vector #'ps/Relation]] 
                                         [:tuple [:vector #'ps/WorkingContext] #'ps/ResolvedContexts]])
(defn resolve-contexts-with-includes [user-contexts fetched-rels max-iterations]
  ;; Fixed point resolution of includes.
  (loop [i 0
         user-contexts user-contexts
         previous-resolved-contexts nil
         resolved-contexts (resolve-contexts user-contexts fetched-rels)]
    (tel/event! ::resolve-contexts-with-includes:iteration-start
                {:data {:i i}})
    (cond
      (= previous-resolved-contexts resolved-contexts)
      (do (tel/event! ::resolve-contexts-with-includes:found-fixed-point)
          [user-contexts resolved-contexts])

      (<= max-iterations (inc i))
      (do (tel/event! ::resolve-contexts-with-includes:hit-max-iterations)
          [user-contexts resolved-contexts])

      :else
      (let [new-user-contexts (user-context-with-include-rels user-contexts resolved-contexts)]
        (recur (inc i)
               new-user-contexts
               resolve-contexts
               (resolve-contexts new-user-contexts fetched-rels))))))

(m/=> resolve-config [:=> [:cat #'ps/UserConfig [:seqable #'ps/AnyMessage]] #'ps/WorkingConfig])
(defn resolve-config [user-config fetched-publication-messages]
  (tel/event! ::resolve-config:start)
  (let [now-instant
        (java.time.Instant/now)

        ;; TODO: Test message filtering
        ;; TODO: Move to it's own function
        message-filters
        [message-filter-valid-publication-message
         message-filter-valid-signature
         message-filter-matching-self-identifier]

        passed-publication-messages
        (filter (apply every-pred message-filters)
                fetched-publication-messages)

        ;; NOTE: Need valid and signed messages in order to extract invalidations
        invalidations
        (mapv message-invalidation passed-publication-messages)

        passed-publication-messages
        (filter 
          (message-filter-invalidations invalidations)
          passed-publication-messages)

        passed-publication-messages
        (filter
          (message-filter-valid-date now-instant)
          passed-publication-messages)

        publication-message-stats
        {:total (count fetched-publication-messages)
         :used  (count passed-publication-messages)
         :filtered (- (count fetched-publication-messages)
                      (count passed-publication-messages))}

        _ (tel/spy! publication-message-stats)

        _ (tel/event! ::resolve-config:filtered-publication-messages)

        user-contexts 
        (:user-contexts user-config)

        fetched-rels 
        (into [] (mapcat #(get-in % [:body :publication/relations]) passed-publication-messages))

        [working-contexts resolved-contexts]
        ;; WIPTODO: Get max iterations from config-options
        (resolve-contexts-with-includes user-contexts fetched-rels 10)

        _ (tel/event! ::resolve-config:resolved-contexts)]
    {:user-config-options (:user-config-options user-config)
     :publication-message-stats publication-message-stats
     :working-contexts working-contexts
     :resolved-contexts resolved-contexts}))
