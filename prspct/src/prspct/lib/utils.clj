(ns prspct.lib.utils
  (:require 
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string]

    [taoensso.telemere :as tel]
    [taoensso.truss :refer [have have! have!? have? ex-info!]]

    [edamame.core :as edamame]

    [babashka.fs :as fs])
  (:import
    [java.time Instant ZoneId]
    [java.security MessageDigest]))

(defn sha256 [^String s]
  (have! string? s)
  (let [hash (MessageDigest/getInstance "SHA-256")]
    (. hash update (.getBytes s))
    (let [digest (.digest hash)]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn build-info []
  (-> "build-info.edn" io/resource slurp edamame/parse-string))

(defn hostname []
  (-> 
    (shell/sh "hostname")
    :out
    clojure.string/trim))

(defn instant->offset-date-time [^Instant instant]
  (.toOffsetDateTime (.atZone instant (ZoneId/systemDefault))))

(comment
  (require '[malli.core :as m]
           '[malli.experimental.time.transform :as mett])

  (m/decode [:time/offset-date-time {:pattern  "EEE, dd MMM yyyy HH:mm:ss Z"}]
    (m/encode [:time/offset-date-time {:pattern  "EEE, dd MMM yyyy HH:mm:ss Z"}] 
              (instant->offset-date-time (Instant/now)) 
              (mett/time-transformer))
    (mett/time-transformer)))


(defmacro with-temp-dir
  "Like fs/with-temp-dir but allows arbitrary temp-dirs.

  Also adds an extra :no-delete option useful for debugging."
  [bindings & body]
  (have! vector? bindings)
  (have! even? (count bindings))
  (cond 
    (= (count bindings) 0) 
    `(do ~@body)

    (and (symbol? (bindings 0))
         (map? (bindings 1)))
    (let [[binding-name options] (subvec bindings 0 2)]
      `(let [~binding-name (fs/create-temp-dir ~options)]
         (try 
           (with-temp-dir ~(subvec bindings 2) ~@body)
           (finally
             (when (not (:no-delete ~options))
               (fs/delete-tree ~binding-name {:force true}))))))))

(comment
  (with-temp-dir [a {}
                  b {:no-delete true}
                  c {}]
    (println a)
    (println b)
    (println c)))
         


    
    


