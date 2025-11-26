(ns prspct.resolution
  [:require 
   [clojure.string :as str]

   [taoensso.telemere :as tel]

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


;; ## User Context Transforms

(defn user-context-transformation-contacts [user-config-options resolved-contexts user-context]
  (let [{:np.contacts/keys [configs]} 
        user-config-options

        contexts (keys resolved-contexts)
        
        contact-kw->contact-ids
        (into {} 
          (for [{:keys [ctx under-namespace]} configs
                :let [parsed-ctx (ps/context->internal-context ctx)
                      matching-contexts (filterv (partial ctx-child? parsed-ctx) contexts)]
                matching-context matching-contexts
                :let [contact-name (last matching-context)
                      context-rels (get resolved-contexts matching-context)
                      context-ids (mapv first context-rels)]]
            [(keyword (name under-namespace) contact-name) context-ids]))
        
        contact-rels
        (mapcat
          (fn [rel]
            (let [{:relation/keys [object-pair]} rel
                  [id ctx] object-pair]
              (for [matched-contact-id (contact-kw->contact-ids id)]
                (assoc rel :relation/object-pair [matched-contact-id ctx]))))
          (:relations user-context))]
    (update user-context :relations into contact-rels)))

(m/=> resolve-contexts [:=> [:cat [:vector #'ps/UserContext] [:vector #'ps/Relation]] 
                            #'ps/ResolvedContexts])
(defn resolve-contexts [user-contexts fetched-rels]
  (tel/event! ::resolve-contexts:start)
  (let [initial-self-rels 
        (into [] 
             (mapcat (fn [{:keys [context relations]}]
                       (mapv #(assoc % :relation/subject-pair [::self context])
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
                              ::self))
                all-rels)

        resolved-contexts
        (into {}
             (map (fn [{:relation/keys [subject-pair]}]
                    (let [[_ ctx] subject-pair]
                      [ctx (rel-graph/rnode-reach subject-pair g)])))
             resolved-self-rels)

        _ (tel/event! ::resolve-contexts:resolved-contexts)]
    resolved-contexts))

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

        ;; stage 0:
        fetched-rels 
        (into [] (mapcat #(get-in % [:body :publication/relations]) passed-publication-messages))

        resolved-contexts-stage-0 
        (resolve-contexts user-contexts fetched-rels)

        _ (tel/event! ::resolve-config:resolved-contexts-stage-0)

        ;; derived user-contexts:
        user-contexts-transformed
        (mapv (partial user-context-transformation-contacts 
                       (:user-config-options user-config) 
                       resolved-contexts-stage-0) user-contexts)

        _ (tel/event! ::resolve-config:derived-user-contexts)

        ;; stage 1:
        resolved-contexts-stage-1 
        (resolve-contexts user-contexts-transformed fetched-rels)

        _ (tel/event! ::resolve-config:resolved-contexts-stage-1)]

    {:user-config-options (:user-config-options user-config)
     :publication-message-stats publication-message-stats
     :working-contexts user-contexts-transformed
     :resolved-contexts resolved-contexts-stage-1}))
