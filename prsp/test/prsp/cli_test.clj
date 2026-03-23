(ns prsp.cli-test
  (:require
   [clojure.java.io]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.generators :as gen]
   [com.gfredericks.test.chuck.generators :as gen']
   [com.gfredericks.test.chuck.clojure-test :refer [checking]]


   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [babashka.fs :as fs]

   [prsp.dsl :as dsl]
   [prsp.schemas :as ps]
   [prsp.test-utils :refer [with-perspects *preserve-test-data*]]
   [prsp.lib.utils :as utils]
   [prsp.cli :as sut]))


(prsp.test-utils/deftest-ns-schemas-test)

(deftest init-test
  (testing "basics"
    (utils/with-temp-dir [base-dir {}]
      (sut/-main "init" "--base-dir" base-dir)
      (is (= (sort (vec (map str (file-seq (fs/file base-dir)))))
             (sort [(str base-dir "")
                    (str base-dir "/relations.edn")
                    (str base-dir "/config.edn")
                    (str base-dir "/.prsp")
                    (str base-dir "/.prsp/fetches")
                    (str base-dir "/.prsp/fetches/0")
                    (str base-dir "/.prsp/fetches/0/fetch-info.edn")
                    (str base-dir "/.prsp/.gitignore")
                    (str base-dir "/.prsp/fetches.HEAD")
                    (str base-dir "/.prsp/fetches.HEAD/fetch-info.edn")]))))))
