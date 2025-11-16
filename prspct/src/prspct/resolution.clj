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
;;TODO: implement

(defn message-filter-valid-publication-message [fetched-publication-message]
  (get-in fetched-publication-message [:headers :prspct.message-transfer/publication-message?]))

(defn message-filter-valid-date [fetched-publication-message]
  fetched-publication-message)

(defn message-filter-valid-signature [fetched-publication-message]
  (let [publication (:body fetched-publication-message)
        ret (prspct.publication/verify-publication publication)]
    (tel/spy! :debug ret)
    (get ret :valid? false)))

(defn message-filter-matching-self-identifier [fetched-publication-message]
  fetched-publication-message)

(defn message-filter-valid-rel-pairs [fetched-publication-message]
  ;; Detect keywords in pairs...
  fetched-publication-message)

(defn message-filter-self [fetched-publication-message]
  ;; Detect keywords in pairs...
  fetched-publication-message)


;; ## User Context Transforms

(defn user-context-transformation-contacts [resolved-contexts user-context]
  (let [{:np.contacts/keys [configs]} 
        (:config user-context)

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
  (let [message-filters
        [message-filter-valid-publication-message
         message-filter-valid-date
         message-filter-valid-signature
         message-filter-matching-self-identifier]

        usable-publication-messages
        (filter (apply every-pred message-filters)
                fetched-publication-messages)

        publication-message-stats
        {:total (count fetched-publication-messages)
         :used  (count usable-publication-messages)
         :filtered (- (count fetched-publication-messages)
                      (count usable-publication-messages))}

        _ (tel/spy! publication-message-stats)

        _ (tel/event! ::resolve-config:filtered-publication-messages)

        user-contexts 
        (:user-contexts user-config)

        ;; stage 0:
        fetched-rels 
        (into [] (mapcat #(get-in % [:body :publication/relations]) usable-publication-messages))

        resolved-contexts-stage-0 
        (resolve-contexts user-contexts fetched-rels)

        _ (tel/event! ::resolve-config:resolved-contexts-stage-0)

        ;; derived user-contexts:
        user-contexts-transformed
        (mapv (partial user-context-transformation-contacts resolved-contexts-stage-0) user-contexts)

        _ (tel/event! ::resolve-config:derived-user-contexts)

        ;; stage 1:
        resolved-contexts-stage-1 
        (resolve-contexts user-contexts-transformed fetched-rels)

        _ (tel/event! ::resolve-config:resolved-contexts-stage-1)]

    {:publication-message-stats publication-message-stats
     :initial-contexts user-contexts
     :working-contexts user-contexts-transformed
     :resolved-contexts resolved-contexts-stage-1}))
