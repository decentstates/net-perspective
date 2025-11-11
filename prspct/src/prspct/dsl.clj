(ns prspct.dsl
  (:refer-clojure :exclude [-> ->>]))

(defn ctx [& more]
  (apply list 'ctx more))

(defn -> [& more]
  (apply list '-> more))

(defn ->> [& more]
  (apply list '->> more))
