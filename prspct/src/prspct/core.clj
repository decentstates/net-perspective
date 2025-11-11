(ns prspct.core
 [:require 
  [taoensso.telemere :as tel]

  [prspct.cli :as cli]]
 (:gen-class))

(defn -main [& args]
  (if (= (apply cli/-main args)
         :error-exit)
    (java.lang.System/exit 1)
    (java.lang.System/exit 0)))

