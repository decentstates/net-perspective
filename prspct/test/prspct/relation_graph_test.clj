(ns prspct.relation-graph-test
  (:require 
    [clojure.test :refer [deftest is testing]]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.generators :as gen']
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]

    [taoensso.telemere :as tel]

    [prspct.schemas :as ps]
    [prspct.test-utils]
    [prspct.relation-graph :as sut]))


(prspct.test-utils/deftest-ns-schemas-test)

;; TODO: more testing

(deftest resolve-graph-globs-test
  (testing "object globs"
    (let [rels 
          [{:relation/subject-pair [1 ["x" "a"]]
            :relation/object-pair  [2 ["x" "*"]]}
           {:relation/subject-pair [2 ["x" "a"]]
            :relation/object-pair  [3 ["x"]]}
           {:relation/subject-pair [2 ["x" "b"]]
            :relation/object-pair  [4 ["x"]]}]

          g
          (sut/relations->rel-graph rels)

          expected-rels-resolved
          [{:relation/subject-pair [1 ["x" "a"]]
            :relation/object-pair  [2 ["x" "a"]]}
           {:relation/subject-pair [1 ["x" "a"]]
            :relation/object-pair  [2 ["x" "b"]]}]

          expected-g-resolved
          (sut/relations->rel-graph expected-rels-resolved)]

      (is (= (dissoc expected-g-resolved :attrs)
             (dissoc (sut/resolve-graph-globs g) :attrs)))))

  (testing "subject globs"
    (let [rels 
          [{:relation/subject-pair [1 ["f" "a"]]
            :relation/object-pair  [2 ["x" "a"]]}
           {:relation/subject-pair [1 ["c" "*"]]
            :relation/object-pair  [2 ["x" "*"]]}
           {:relation/subject-pair [2 ["x" "a"]]
            :relation/object-pair  [3 ["x"]]}
           {:relation/subject-pair [2 ["x" "b"]]
            :relation/object-pair  [4 ["x"]]}]

          g
          (sut/relations->rel-graph rels)

          expected-rels-resolved
          [{:relation/subject-pair [1 ["c" "a"]]
            :relation/object-pair  [2 ["x" "a"]]}
           {:relation/subject-pair [1 ["c" "b"]]
            :relation/object-pair  [2 ["x" "b"]]}]

          expected-g-resolved
          (sut/relations->rel-graph expected-rels-resolved)]

      (is (= (dissoc expected-g-resolved :attrs)
             (dissoc (sut/resolve-graph-globs g) :attrs)))))

  (testing "subject glob cycles"
    (let [rels 
          [{:relation/subject-pair [1 ["f" "a"]]
            :relation/object-pair  [2 ["x" "a"]]}
           {:relation/subject-pair [1 ["c" "g"]]
            :relation/object-pair  [5 ["x" "d"]]}
           {:relation/subject-pair [1 ["c" "*"]]
            :relation/object-pair  [2 ["x" "*"]]}
           {:relation/subject-pair [2 ["x" "a"]]
            :relation/object-pair  [3 ["x"]]}
           {:relation/subject-pair [2 ["x" "b"]]
            :relation/object-pair  [4 ["x"]]}
           {:relation/subject-pair [4 ["x" "x"]]
            :relation/object-pair  [5 ["x" "d"]]}
           {:relation/subject-pair [2 ["x" "*"]]
            :relation/object-pair  [1 ["c" "*"]]}]

          g
          (sut/relations->rel-graph rels)

          expected-rels-resolved
          [{:relation/subject-pair [1 ["c" "a"]]
            :relation/object-pair  [2 ["x" "a"]]}
           {:relation/subject-pair [1 ["c" "b"]]
            :relation/object-pair  [2 ["x" "b"]]}
           {:relation/subject-pair [1 ["c" "g"]]
            :relation/object-pair  [2 ["x" "g"]]}
           {:relation/subject-pair [2 ["x" "a"]]
            :relation/object-pair  [1 ["c" "a"]]}
           {:relation/subject-pair [2 ["x" "b"]]
            :relation/object-pair  [1 ["c" "b"]]}
           {:relation/subject-pair [2 ["x" "g"]]
            :relation/object-pair  [1 ["c" "g"]]}]

          expected-g-resolved
          (sut/relations->rel-graph expected-rels-resolved)]

      (is (= (dissoc expected-g-resolved :attrs)
             (dissoc (sut/resolve-graph-globs g) :attrs))))))

(comment
  (resolve-graph-globs-test))
