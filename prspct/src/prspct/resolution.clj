(ns prspct.resolution
  [:require 
   [clojure.string :as str]

   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [malli.core :as m]

   [loom.graph]

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

(m/=> resolve-relgraph [:=> [:cat [:vector #'ps/UserContext] [:vector #'ps/Relation]] 
                        #'rel-graph/RelGraph])
(defn resolve-relgraph 
  [user-contexts fetched-rels]
  (tel/event! ::resolve-graph:start)
  (let [initial-self-rels 
        ;; Globbing may cause more self-rels to be created, hence initial vs resolved self-rels
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

        _ (tel/event! ::resolve-graph:calculated-glob-rels)

        all-rels 
        (concat fetched-rels initial-self-rels glob-rels)

        g
        (rel-graph/relations->rel-graph all-rels)

        _ (tel/event! ::resolve-graph:generated-graph)]
    g))


(m/=> relgraph->resolved-contexts [:=> [:cat #'rel-graph/RelGraph [:set #'ps/UserConfigIdentifier]] 
                                   #'ps/ResolvedContexts])
(defn relgraph->resolved-contexts [g idents-to-resolve]
  (let [all-pairs 
        (loom.graph/nodes g)

        pairs-to-resolve
        (filterv (fn [[ident _context]] 
                   (contains? idents-to-resolve ident))
                 all-pairs)
        
        _ (tel/event! ::relgraph->resolved-contexts:calculated-rels-to-resolve)
  
        resolved-contexts
        (into {}
              (map (fn [subject-pair]
                     (let [[_ ctx] subject-pair]
                       [ctx (rel-graph/rnode-reach subject-pair g)])))
              pairs-to-resolve)

        _ (tel/event! ::relgraph->resolved-contexts:resolved-contexts)]
    resolved-contexts))

(defn include-ident->idents [resolved-contexts include-ident]
  (let [context-to-include
        (ps/include-ident->internal-context include-ident)
        
        identities-to-include
        (mapv first (get resolved-contexts context-to-include))]
    identities-to-include))
  

(defn resolve-include-ident-rel [resolved-contexts rel]
  (let [context-to-include
        (ps/include-ident-rel->internal-context rel)

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
                       (filterv ps/include-ident-rel? relations)
                       
                       includes
                       (mapv (partial resolve-include-ident-rel resolved-contexts) include-relations)]
                   (vec (distinct (apply concat relations includes)))))))
    user-contexts))

(m/=> resolve-fixed-point-contexts-relgraph [:=> [:cat [:vector #'ps/UserContext] [:vector #'ps/Relation] [:set #'ps/UserConfigIdentifier] :int] 
                                             [:tuple [:vector #'ps/WorkingContext] #'rel-graph/RelGraph]])
(defn resolve-fixed-point-contexts-relgraph [user-contexts fetched-rels idents-to-resolve max-iterations]
  ;; Fixed point resolution of includes.
  (loop [i 0
         user-contexts user-contexts
         previous-resolved-contexts nil
         g (resolve-relgraph user-contexts fetched-rels)]
    (tel/event! ::resolve-fixed-point-contexts-relgraph:iteration-start
                {:data {:i i}})
    (let [resolved-contexts (relgraph->resolved-contexts g idents-to-resolve)]
      (cond
        (= previous-resolved-contexts resolved-contexts)
        (do (tel/event! ::resolve-fixed-point-contexts-relgraph:fixed-point-found)
            [user-contexts g])

        (<= max-iterations (inc i))
        (do (tel/event! ::resolve-fixed-point-contexts-relgraph:fixed-point-hit-max-iterations
                        :warn)
            [user-contexts g])

        :else
        (let [new-user-contexts (user-context-with-include-rels user-contexts resolved-contexts)]
          (recur (inc i)
                 new-user-contexts
                 resolved-contexts
                 (resolve-relgraph new-user-contexts fetched-rels)))))))


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

        [working-contexts relgraph]
        ;; WIPTODO: Get max iterations from config-options
        (resolve-fixed-point-contexts-relgraph user-contexts fetched-rels #{:self} 10)

        _ (tel/event! ::resolve-config:resolved-contexts-and-relgraph)]
   {:user-config-options (:user-config-options user-config)
    :publication-message-stats publication-message-stats
    :working-contexts working-contexts
    ;; WIPTODO: Move out of here and simplify
    :relgraph relgraph
    ;; WIPTODO: Remove resolved-contexts from here
    :resolved-self-contexts (relgraph->resolved-contexts relgraph #{:self})}))
