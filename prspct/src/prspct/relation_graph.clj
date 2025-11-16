(ns prspct.relation-graph
  "A relation graph, relgraph for short"
  (:require 
    [clojure.string :as str]

    [cognitect.anomalies :as anom]

    [taoensso.telemere :as tel]
    [taoensso.truss :refer [have have! have!? have? ex-info!]]

    [malli.core :as m]
    [malli.generator :as mg]

    [loom.alg]
    [loom.attr :refer [add-attr-to-nodes add-attr-to-edges attr attrs]] 
    [loom.derived]
    [loom.graph :refer [digraph add-edges nodes edges successors]]
    [loom.io]

    [prspct.schemas :as ps]))



;; TODO: look into jgrapht as a replacement

;; ## derived graphs with preservation

;; Need to be very careful with loom as derived graph functions do not preserve
;; attributes.

(defn copy-attrs 
  "Copies attributes from one graph to another"
  [src-g dst-g]
  (as-> dst-g $g
    (reduce
      (fn [g node]
        (reduce
          (fn [g [k v]]
            (add-attr-to-nodes g k v [node]))
          g
          (attrs src-g node)))
      $g (nodes dst-g))
    (reduce
      (fn [g edge]
        (reduce
          (fn [g [k v]]
            (add-attr-to-edges g k v [edge]))
          g
          (attrs src-g edge)))
      $g (edges dst-g))))


;; ## Condensed Graphs: [condensed-g, condensed-node->components]

(defn- rnode-condensed [id]
  [[::condensed-node id] []])

(defn- rnode-condensed-id [rnode]
  (second (first rnode)))

(defn- rnode-condensed? [rnode]
  (let [[ident _context] rnode]
    (and (vector? ident)
         (= ::condensed-node (first ident)))))
      
(defn condense-graph [g] ;; -> g + loop-node->sub-graph
  (let [strongly-connected-components
        (filterv #(< 1 (count %)) (loom.alg/scc g))
        
        condensed-node->components
        (into {} 
              (map-indexed 
                (fn [i components] 
                  [(rnode-condensed i) (set components)]))
              strongly-connected-components)

        condensed-g
        (->> g
          (loom.derived/mapped-by
            (fn [node]
              (if-let [condensed-node (some (fn [[condensed-node components]] 
                                              (and (contains? components node) condensed-node))
                                            condensed-node->components)]
                condensed-node
                node)))
          (loom.derived/edges-filtered-by
            (fn [[from to]]
              (not (and (rnode-condensed? from)
                        (rnode-condensed? to))))))]
    [condensed-g condensed-node->components]))


;; ## Relation graphs:
;; rnode -> subject/object-pair

(defn relations->rel-graph [rels]
  (reduce (fn [g rel]
            (let [edge [(:relation/subject-pair rel) (:relation/object-pair rel)]]
              (-> g
                  (add-edges edge)
                  ;; Note, only add transitive
                  (add-attr-to-edges :relation/transitive? (:relation/transitive? rel) [edge])
                  (add-attr-to-edges :relation/public? (:relation/public? rel) [edge]))))
          (digraph)
          rels))

(defn rel-graph->relations [g]
  (mapv 
    (fn [[sub obj]] 
      (merge 
        (attrs g sub obj)
        {:relation/subject-pair sub 
         :relation/object-pair obj})) 
    (edges g)))
  

(defn generate-rels []
  (let [rels 
        (into [] cat (vals (mg/generate ps/SubjectPair->Relations)))

        ensure-context-matcher 
        (fn [[identifier context]]
          [identifier (if (str/ends-with? context ".*")
                        context
                        (str context ".*"))])

        rels-obj-globbed 
        (mapv (fn [rel]
                (-> rel
                    (update :relation/object-pair ensure-context-matcher)
                    (assoc :relation/transitive? true)))
          rels)

        rels-sub-globbed 
        (mapv (fn [rel]
                (-> rel
                    (update :relation/subject-pair ensure-context-matcher)
                    (update :relation/object-pair ensure-context-matcher)
                    (assoc :relation/transitive? true)))
          rels)

        obj-to-external-sub 
        (fn [[identifier context] extra]
          (if (str/ends-with? context ".*")
            [identifier 
             (str (str/replace context ".*" ".")
                  extra)]
            [identifier context]))
        
                  
        rels-external
        (map-indexed (fn [i rel]
                       {:relation/subject-pair
                        (obj-to-external-sub (:relation/object-pair rel)
                                             (str "x" i))

                        :relation/object-pair 
                        [(str "uri:rss:feed-y-" i) "#null"]

                        :relation/public? 
                        false

                        :relation/transitive? 
                        false})
                     rels)

        all-rels 
        (vec (concat rels-sub-globbed rels-obj-globbed rels-external))]
       all-rels))

(defn rnode-reach [rnode g]
  (let [g-transitive 
        (loom.derived/edges-filtered-by #(attr g % :relation/transitive?) g)

        g-non-transitive 
        (loom.derived/edges-filtered-by (complement #(attr g % :relation/transitive?)) g)

        g-reach-transitive
        (loom.derived/subgraph-reachable-from g-transitive rnode)
        
        g-reach
        (loom.derived/subgraph-reachable-from (digraph g-reach-transitive g-non-transitive) rnode)

        g-reach-with-attrs (copy-attrs g g-reach)
        
        reach-rnodes (set (nodes g-reach-with-attrs))]
    ;; We don't want the initial node
    (disj reach-rnodes rnode)))


;; ## glob resolution

(defn- rnode-globbed? [rnode]
  (let [[_ obj-context] rnode]
    (= (last obj-context)
       "*")))

(defn- glob-matches 
  "Returns an array of (rnode, globbed-context-part)
  
  Matches only direct children."
  [all-rnodes glob-rnode]
  (let [[glob-ident glob-context]
        glob-rnode

        _
        (have! (partial = "*") (last glob-context))

        context-prefix
        (vec (butlast glob-context))] 
    (into []
      (comp
        (map (fn [[ident context]]
               (when (and (= ident glob-ident)
                          ;; NOTE: Restricting to one child, double globs is
                          ;;       unimplemented but will have a similar
                          ;;       implementation, but adding in further edges into
                          ;;       the graph to indicate dependencies between
                          ;;       contexts of a user with globs.
                          (= (count context) (inc (count context-prefix)))
                          (= (subvec context 0 (count context-prefix))
                             context-prefix))
                 [[ident context] (subvec context (count context-prefix))])))
        (filter identity)
        (filter #(not= glob-rnode (first %))))
      all-rnodes)))

;; TODO: Consistent naming throughout: sub vs subj, and node vs pair vs no-suffix, ctx vs context
(defn- fulfil-sub-glob-edge [g edge match]
  (let [[[sub-ident sub-ctx] _obj-rnode]
        edge

        _
        (have! (partial = "*") (last sub-ctx))

        sub-ctx-prefix
        (vec (butlast sub-ctx))

        [matching-obj-rnode matching-context-part]
        match]
    [[sub-ident (into sub-ctx-prefix matching-context-part)]
     matching-obj-rnode
     (assoc (attrs g edge) ::from-sub-glob edge)]))

(defn- fulfil-obj-glob-edge [g edge match]
  (let [[sub-rnode _obj-rnode] edge
        [matching-obj-rnode _matching-context-part] match]
    [sub-rnode 
     matching-obj-rnode 
     (assoc (attrs g edge) ::from-obj-glob edge)]))


;; TODO: loom attr support is bad, maybe JGraphT
(defn add-edges-with-attrs 
  "Add edges as triples: [from to edge-attrs]"
  [g edge-triples]
  (let [g-with-edges (apply add-edges g (mapv butlast edge-triples))]
    (reduce (fn [acc-g [from to attrs]]
              (reduce (fn [acc-g [k v]]
                        (loom.attr/add-attr-to-edges acc-g k v [[from to]]))
                      acc-g
                      attrs))
            g-with-edges
            edge-triples)))

(defn- resolve-sub-glob-condensed 
  "Produces new edges [sub obj attr] based on a sub-glob (condensed.)"
  [g condensed-node->components acc-g [sub-rnode obj-rnode]]
  (have! (and (rnode-condensed? sub-rnode)
              (= sub-rnode obj-rnode)))
  (let [components
        (condensed-node->components sub-rnode)

        in-condensed-node-g
        (loom.derived/nodes-filtered-by (set components) g)

        in-condensed-edges
        (edges in-condensed-node-g)

        all-rnodes
        (concat (nodes g) (nodes acc-g))

        all-matches
        (into []
              (comp
                (map second)
                (mapcat (partial glob-matches all-rnodes)))
              in-condensed-edges)

        produced-edges
        (vec (for [e in-condensed-edges
                   m all-matches]
               (fulfil-sub-glob-edge g e m)))]
    (filterv
      (fn [[sub obj _attrs]]
        (not= sub obj))
      produced-edges)))
    
(defn- resolve-sub-glob-non-condensed 
  "Produces new edges [sub obj attr] based on a sub-glob (non-condensed.)"
  [g acc-g edge]
  (let [[_sub-rnode obj-rnode] edge
        all-rnodes (concat (nodes g) (nodes acc-g))
        matches (glob-matches all-rnodes obj-rnode)]
    (mapv (partial fulfil-sub-glob-edge g edge)
          matches)))

(defn- resolve-obj-glob 
  "Produces new edges [sub obj attr] based on a obj-glob"
  [g acc-g edge]
  (let [[_sub-rnode obj-rnode] edge
        all-rnodes (concat (nodes g) (nodes acc-g))
        matches (glob-matches all-rnodes obj-rnode)]
    (mapv (partial fulfil-obj-glob-edge g edge)
          matches)))

(defn- resolve-edge-globs [g condensed-node->components acc-g edge]
  (let [sub (first edge)
        sub-condensed? (rnode-condensed? sub)
        sub-globbed? (rnode-globbed? sub)
        obj (second edge)
        obj-condensed? (rnode-condensed? obj)
        obj-globbed? (rnode-globbed? obj)

        new-edges
        (cond
          (and sub-condensed? (= sub obj))
          (resolve-sub-glob-condensed g condensed-node->components acc-g edge)

          (or sub-condensed? obj-condensed?)
          (ex-info! "edge with only one condensed node is unknown semantics"
                    {::anom/category ::anom/incorrect})

          (and sub-globbed? obj-globbed?)
          (resolve-sub-glob-non-condensed g acc-g edge)

          (and (not sub-globbed?) obj-globbed?)
          (resolve-obj-glob g acc-g edge)
      
          (and sub-globbed? (not obj-globbed?))
          (ex-info! "sub glob without obj glob is unsupported" 
                          {::anom/category ::anom/unsupported})

          :else
          (ex-info! "unhandled/unknown case"
                    {::anom/category ::anom/fault}))]
    (add-edges-with-attrs acc-g new-edges)))

(defn glob-rnode->edges-to-resolve 
  [glob-g condensed-node->components rnode]
  (if-not (rnode-condensed? rnode)
    (mapv #(vector rnode %) (successors glob-g rnode))
    (conj
      (into []
            (mapcat (fn [n] 
                      (mapv #(vector n %) (successors glob-g n))))
            (condensed-node->components rnode))
      [rnode rnode])))

(defn resolve-graph-globs 
  "Produces a new graph of relations that resolve all globs. O(n)."
  [g]
  ;; - We must correctly sort, subject and object globs rely on subjects
  ;;   existing, subject globs create new subjects.
  ;; - We condense sub-glob cycles to a single node
  ;;   - This allows us to correctly topologically sort
  ;;   - We resolve the whole sub-glob cycle simultanaeously
  ;; - We determine an ordered list of edges to resolve.
  ;; - Each edge produces new edges
  (tel/event! ::resolve-graph-globs:start)
  (let [nodes-with-glob-edges (into #{}
                                  (comp
                                    (filter #(some rnode-globbed? %))
                                    cat)
                                  (edges g))

        glob-g 
        (loom.derived/nodes-filtered-by #(contains? nodes-with-glob-edges %) g)

        _ (tel/event! ::resolve-graph-globs:filtered-globs)

        [glob-cg condensed-node->components] 
        (condense-graph glob-g)

        _ (tel/event! ::resolve-graph-globs:condensed-subject-globs)

        ordered-glob-rnodes-to-resolve 
        (reverse (loom.alg/topsort glob-cg))

        _ (tel/event! ::resolve-graph-globs:topologically-sorted-condensed-subject-globs)

        ordered-glob-edges-to-resolve 
        (into []
              (mapcat (partial glob-rnode->edges-to-resolve glob-g condensed-node->components))
              ordered-glob-rnodes-to-resolve)

        _ (tel/event! ::resolve-graph-globs:calculated-edges-to-resolve)

        resolved-edge-globs
        (reduce (partial resolve-edge-globs g condensed-node->components)
                (digraph) ordered-glob-edges-to-resolve)

        _ (tel/event! ::resolve-graph-globs:resolved-graph-globs)]

    resolved-edge-globs))

(comment
  (let [rels 
        (generate-rels)

        g
        (relations->rel-graph rels)]

    (loom.derived/nodes-filtered-by rnode-globbed? g)

    (time (resolve-graph-globs g))))
