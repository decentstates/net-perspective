(ns prsp.lib.utils
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string]

   [cognitect.anomalies :as anom]

   [taoensso.telemere :as tel]
   [taoensso.truss :refer [have have! have!? have? ex-info!]]

   [edamame.core :as edamame]

   [babashka.fs :as fs])
  (:import
   [java.time Instant ZoneId]
   [java.security MessageDigest]))


(def ^:dynamic *sh-cwd* nil)

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

  Also adds an extra :preserve option useful for debugging."
  {:clj-kondo/lint-as 'clojure.core/let}
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
             (when (not (:preserve ~options))
               (fs/delete-tree ~binding-name {:force true}))))))))

(comment
  (with-temp-dir [a {}
                  b {:preserve true}
                  c {}]
    (println a)
    (println b)
    (println c)))


(defn sh [& args]
  (let [args (if *sh-cwd*
               (into (vec args)
                     [:dir *sh-cwd*])
               args)]
    (apply shell/sh args)))

(defn ssh-keygen [& args]
  (let [args (into ["ssh-keygen"] args)
        ret (apply sh args)]
    (assoc ret :args args)))

(defn assert-sh-ret [ret purpose]
  (when (not= 0 (:exit ret))
    (ex-info! (str purpose ": ssh-keygen command failed")
              {::anom/category ::anom/fault
               :instructions (:err ret)
               :ret ret})))

(defmacro with-temp-key-pairs
  {:clj-kondo/lint-as 'clojure.core/let}
  [bindings & body]
  (have! vector? bindings)
  (have! even? (count bindings))
  (cond
    (= (count bindings) 0)
    `(do ~@body)

    (and (symbol? (bindings 0))
         (map? (bindings 1)))

    (let [[binding-name options] (subvec bindings 0 2)
          temp-dir-options (select-keys options [:preserve])]
      `(with-temp-dir [d# ~temp-dir-options]
         (let [~binding-name
               (let [private-key-path# (str d# "/key")
                     public-key-path# (str d# "/key.pub")]
                 (have! (ssh-keygen "-t" "ed25519" "-f" private-key-path# "-q" "-N" ""))
                 (have! fs/exists? private-key-path#)
                 (have! fs/exists? public-key-path#)
                 {:private
                  private-key-path#

                  :public
                  public-key-path#})]
           (with-temp-key-pairs ~(subvec bindings 2) ~@body))))))

(comment
  (require '[clojure.pprint])
  (with-temp-key-pairs [a {}
                        b {}]
    (clojure.pprint/pprint a)
    (clojure.pprint/pprint b)))

(defn random-guerrilla-mail []
  (let [uid (str (random-uuid))
        short (subs (clojure.string/replace uid "-" "") 0 8)]
    (str "prsp-" short "@guerrillamail.com")))

(defn xdg-config-home
  "Returns the XDG config home directory, honouring $XDG_CONFIG_HOME if set."
  []
  (or (System/getenv "XDG_CONFIG_HOME")
      (str (fs/path (fs/home) ".config"))))

(defn multigroup-by
  "Group by items in an array, if there are multiple items the element will be in more than one
  group, returns a set. (f x) must return a sequential."
  [f coll]
  (as-> coll $
    (into []
          (mapcat
           (fn [x]
             (mapv (fn [group-id] [group-id x])
                   (have! sequential? (f x)))))
          $)
    (group-by first $)
    (update-vals $ (fn [xs] (into #{} (map second) xs)))))
