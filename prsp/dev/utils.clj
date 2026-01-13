(ns utils
  [:require 
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]

   [loom.io]])


;; File outputs

(defonce ^:private output-i (atom 0))
(defonce ^:private last-output (atom nil))

(defn output [output-stream suffix]
  (let [i   (swap! output-i inc)
        pid (.pid (java.lang.ProcessHandle/current))
        f (str "/tmp/prsp/" pid "/output-" (format "%04d" i) suffix)]
    (io/make-parents f)
    (spit f output-stream)
    (println "Wrote output to" f)
    (reset! last-output f)))

(defn open [f]
  (shell/sh "xdg-open" f)
  nil)

(defn output-g [g]
  (output (String. ^bytes (loom.io/render-to-bytes g :fmt :svg)
                   java.nio.charset.StandardCharsets/UTF_8)
          ".svg"))

