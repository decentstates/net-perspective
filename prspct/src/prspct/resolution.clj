(ns prspct.resolution
  [:require 
   [clojure.string :as str]

   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [malli.core :as m]

   [loom.graph]

   [prspct.relation-graph :as rel-graph]
   [prspct.publication :as publication]
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
  (let [;; TODO: Pass time through
        now-instant
        (java.time.Instant/now)

        flagged-publication-messages
        (publication/flag-messages now-instant fetched-publication-messages)

        passed-publication-messages
        (filter publication/passing-message? flagged-publication-messages)

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
        ;; TODO: Get max iterations from config-options
        (resolve-fixed-point-contexts-relgraph user-contexts fetched-rels #{:self} 10)

        _ (tel/event! ::resolve-config:resolved-contexts-and-relgraph)]
   (doseq [m flagged-publication-messages]
     (when-not (publication/passing-message? flagged-publication-messages)
       (tel/trace! ::filtered-publication-message (:headers m))))
   {:user-config-options (:user-config-options user-config)
    :publication-message-stats publication-message-stats
    :working-contexts working-contexts
    :relgraph relgraph
    :resolved-self-contexts (relgraph->resolved-contexts relgraph #{:self})}))
