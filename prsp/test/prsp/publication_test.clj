(ns prsp.publication-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.gfredericks.test.chuck.clojure-test :refer [checking]]
   [com.gfredericks.test.chuck.generators :as gen']

   [taoensso.telemere :as tel]

   [malli.generator :as mg]

   [prsp.lib.utils :as utils]
   [prsp.schemas :as ps]
   [prsp.publication :as sut]
   [prsp.message-transfer :as message-transfer]
   [prsp.test-utils]))

(prsp.test-utils/deftest-ns-schemas-test)

(deftest ssh-signatures-test
  (testing "symmetry"
    (doseq [s ["a" "b" "hello" ""]]
      (utils/with-temp-key-pairs [pair {}]
        (let [sig (sut/ssh-signature s (:private pair))
              corrupt-sig (str/replace-first sig #"\n" "\nA")
              corrupt-s (str "corrupted-" s)]
          (is (= true
                 (sut/verify-ssh-signature s sig (:public pair))))
          (is (= false
                 (sut/verify-ssh-signature s corrupt-sig (:public pair))))
          (is (= false
                 (sut/verify-ssh-signature corrupt-s sig (:public pair))))
          (is (= false
                 (sut/verify-ssh-signature corrupt-s corrupt-sig (:public pair)))))))))

(deftest publication-signature-test
  (utils/with-temp-key-pairs [pair {}]
    (checking "symmetry" 10
              [publication-message (mg/generator #'ps/PublicationMessage)]
              (utils/with-temp-dir [d {:preserve true}]
                (let [publication-message
                      (-> publication-message
                          (update :headers dissoc :x-np-signature)
                          (assoc-in [:body :publication/self-identifier]
                                    (ps/ssh-public-key->identifier-ssh (slurp (:public pair))))
                          (assoc-in [:headers :x-np-id]
                                    (ps/ssh-public-key->identifier-ssh (slurp (:public pair)))))

                      save-message!
                      (fn [m]
                        (assoc-in m [:headers :prsp.message-transfer/file-path]
                                  (message-transfer/write-edn-message!
                                   ps/PublicationMessage
                                   m
                                   d)))

                      publish-to-config
                      {:publisher :main
                       :name "Alice"
                       :email "alice@example.com"
                       :ssh-key-id/public-key-path (:public pair)
                       :ssh-key-id/private-key-path (:private pair)}

                      signed-publication-message
                      (save-message!
                       (sut/sign-publication-message publish-to-config publication-message))

                      corrupted-relations-signed-publication-message
                      (-> signed-publication-message
                          (assoc-in [:body :publication/relations] ["corrupted-relations-data"])
                          save-message!)

                      corrupted-self-identifier-signed-publication-message
                      (-> signed-publication-message
                          (assoc-in [:body :publication/self-identifier] "email:charlie@example.com")
                          save-message!)

                      corrupted-signature-signed-publication-message
                      (-> signed-publication-message
                          (assoc-in [:headers :x-np-signature] "asdf")
                          save-message!)]
                  (is (= {:valid? true}
                         (sut/verify-publication-message signed-publication-message)))
                  (is (= {:valid? false :issues [:failed-signature-verification]}
                         (sut/verify-publication-message corrupted-relations-signed-publication-message)))
                  (is (= {:valid? false :issues [:non-matching-self-identifier]}
                         (sut/verify-publication-message corrupted-self-identifier-signed-publication-message)))
                  (is (= {:valid? false :issues [:invalid-publication-signature ::sut/could-not-decode-json]}
                         (sut/verify-publication-message corrupted-signature-signed-publication-message))))))))

(comment
  (tel/with-min-level :debug
    (publication-signature-test)))
