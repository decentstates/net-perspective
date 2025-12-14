(ns prspct.message-transfer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.generators :as gen]
   [com.gfredericks.test.chuck.generators :as gen']
   [com.gfredericks.test.chuck.clojure-test :refer [checking]]

   [malli.generator :as mg]

   [babashka.fs :as fs]

   [prspct.schemas :as ps]
   [prspct.test-utils]
   [prspct.message-transfer :as sut]))

(prspct.test-utils/deftest-ns-schemas-test)

(deftest shell-source-test
  (testing "basics"
    (fs/with-temp-dir [d {}]
      (is (= #:transfer-result{:success? true
                               :exception nil
                               :out (str "cp /from/here " d "\n")
                               :err ""}
             (sut/source-fetch! (sut/shell-source {:shell/args ["echo" "cp" "/from/here" :output-dir]}) d))))))

(deftest fetch-test
  (testing "basics"
    (let [fetch
          (fs/with-temp-dir [d {}]
            (sut/fetch! [{:shell/args ["echo" "cp" "/from/a" :output-dir]}
                         {:shell/args ["echo" "cp" "/from/b" :output-dir]}]
                        d))]
      (is (= 2 (count (:fetch-info/sources fetch))))
      (is (every? :fetch-info-source/success? (vals (:fetch-info/sources fetch)))))))

(deftest publish-test
  (testing "basics"
    (fs/with-temp-dir [parent-input-dir {}]
      (fs/with-temp-dir [publish-dir {}]
        (let [envelopes (mg/generate [:vector ps/EDNMessageEnvelope])
              envelopes' (mapv #(assoc % :publisher {:shell/args ["find" :input-dir "-name" "*.eml"
                                                                  "-exec" "cp" "{}" (str publish-dir) ";"]})
                               envelopes)

              publish-config->input-dir (sut/write-edn-message-envelopes! envelopes' parent-input-dir)
              _publish-info (sut/publish! publish-config->input-dir parent-input-dir)]
          (is (= (count (fs/glob publish-dir "**.eml"))
                 (count envelopes))))))))
