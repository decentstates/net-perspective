(ns prspct.publication-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]
    [com.gfredericks.test.chuck.generators :as gen']

    [taoensso.telemere :as tel]

    [malli.generator :as mg]

    [prspct.lib.utils :as utils]
    [prspct.schemas :as ps]
    [prspct.publication :as sut]
    [prspct.test-utils]))

(prspct.test-utils/deftest-ns-schemas-test)

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
              [publication (mg/generator #'ps/Publication)]
        (let [;; The generated publication sometimes contains :publication/signature
              publication
              (-> publication
                  (dissoc :publication/signature)
                  (assoc :publication/self-identifier
                         (ps/ssh-public-key->identifier-ssh (slurp (:public pair)))))

              publish-to-config 
              {:publisher :main
               :name "Alice"
               :email "alice@example.com"
               :ssh-key-id/public-key-path (:public pair)
               :ssh-key-id/private-key-path (:private pair)}

              signed-publication
              (sut/sign-publication publish-to-config publication)

              corrupted-signed-publication
              (assoc signed-publication :publication/self-identifier "email:charlie@example.com")

              corrupted-signature-signed-publication
              (assoc signed-publication :publication/signature "asdf")]
          (is (= true 
                 (:valid? (sut/verify-publication signed-publication))))
          (is (= false 
                 (:valid? (sut/verify-publication corrupted-signed-publication))))
          (is (= false 
                 (:valid? (sut/verify-publication corrupted-signature-signed-publication))))))))

(comment
  (tel/with-min-level :debug
    (publication-signature-test)))

