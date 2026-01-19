(ns prsp.lib.system
  "Utils for wiring a reloadable system.

   See:
   - https://medium.com/@maciekszajna/reloaded-workflow-out-of-the-box-be6b5f38ea98
   - https://www.juxt.pro/blog/clojure-in-griffin/"
  (:gen-class))

(defn closeable
  ([value] (closeable value identity))
  ([value close] (reify
                   clojure.lang.IDeref
                   (deref [_] value)
                   java.io.Closeable
                   (close [_] (close value)))))

(defn wait-forever [_state]
  @(promise))

(defn publishing-state [do-with-state target-atom]
  #(do
     (reset! target-atom %)
     (try (do-with-state %)
          (finally (reset! target-atom nil)))))
