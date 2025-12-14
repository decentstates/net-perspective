(ns prspct.core
  [:require
   [taoensso.telemere :as tel]

   [prspct.cli :as cli]]
  (:gen-class))

(defn -main [& args]
  (tel/add-handler! ::console (tel/handler:console {:stream *err*}))
  (tel/remove-handler! :default/console)
  (let [ret (apply cli/-main args)]
    (tel/stop-handlers!)
    (if (= :error-exit ret)
      (java.lang.System/exit 1)
      (java.lang.System/exit 0))))

