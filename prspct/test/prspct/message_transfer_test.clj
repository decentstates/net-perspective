(ns prspct.message-transfer-test
  (:require 
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.generators :as gen']
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]

    [malli.core :as m]
    [malli.error :as me]
    [malli.generator :as mg]
    [malli.instrument :as mi]

    [babashka.fs :as fs]

    [prspct.schemas :as ps]
    [prspct.test-utils]
    [prspct.message-transfer :as sut]))

(prspct.test-utils/deftest-ns-schemas-test)

(deftest shell-source-test
  (testing "basics"
    (fs/with-temp-dir [d {}] 
      (is (= (sut/source-fetch! (sut/shell-source ["echo" "cp" "/from/here" :output-dir]) d)
             #:transfer-result{:success? true
                               :exception nil
                               :out (str "cp /from/here " d "\n")
                               :err ""})))))

(deftest fetch-test
  (testing "basics"
    (let [fetch
          (fs/with-temp-dir [d {}] 
            (sut/fetch! [(sut/source-config (sut/shell-source ["echo" "cp" "/from/a" :output-dir]))
                         (sut/source-config (sut/shell-source ["echo" "cp" "/from/b" :output-dir]))]
                       d))]
      (is (= 2 (count (:fetch-info/sources fetch))))
      (is (every? :fetch-info-source/success? (vals (:fetch-info/sources fetch))))))) 

(deftest publish-test
  (testing "basics"
    (fs/with-temp-dir [parent-input-dir {}]
      (fs/with-temp-dir [publish-dir {}]
        (let [envelopes (mg/generate [:vector ps/EDNMessageEnvelope])
              envelopes' (mapv #(assoc % :publisher {:publisher/fn 'prspct.message-transfer/shell-publisher
                                                     :publisher/args ["find" :input-dir "-name" "*.eml" 
                                                                      "-exec" "cp" "{}" (str publish-dir) ";"]})
                               envelopes)

              publish-config->input-dir (sut/write-edn-message-envelopes! envelopes' parent-input-dir)
              publish-info (sut/publish! publish-config->input-dir parent-input-dir)]
          (is (= (count envelopes) (count (fs/glob publish-dir "**.eml")))))))))
