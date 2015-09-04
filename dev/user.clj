(ns user
  (:require [dumpr.core :as dumpr]
            [dumpr.table-schema :as table-schema]
            [clojure.core.async :as async :refer [<! go go-loop]]
            [taoensso.timbre :as log]))

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/error ex "Uncaught exception on" (.getName thread)))))


(defn sink
  "Returns an atom containing a vector. Consumes values from channel
  ch and conj's them into the atom."
  ([ch] (sink ch identity))
  ([ch pr]
   (let [a (atom [])]
     (go-loop []
       (let [val (<! ch)]
         (if-not (nil? val)
           (do
             (pr val)
             (swap! a conj val)
             (recur))
           (pr "Channel closed."))))
     a)))

(defn sink-and-print [ch] (sink ch println))


(def conn-params
  {;; :subname "//127.0.0.1:3306/sharetribe_development?zeroDateTimeBehavior=convertToNull" ; Autogenerated when not given
   :user "root"
   :password "not-root"
   :host "127.0.0.1"
   :port 3306
   :db "sharetribe_development"
   :server-id 123})

(def id-fns
  {:people :id})

(comment
  (def conf (dumpr/create-conf conn-params id-fns))

  (def res (dumpr/load-tables [:communities :people :listings] conf))
  (def out-rows (sink (:out res)))
  (count @out-rows)
  (table-schema/load-schema (:db-spec conf) "sharetribe_development" (first table-specs))
  (vec (take 2 (first @out-rows)))
  (last @out-rows)
  (go (println (<! (:out res))))

  (def stream (dumpr/binlog-stream conf (:binlog-pos res)))
  (def out-events (sink (:out stream)))
  (dumpr/start-binlog-stream stream)
  (dumpr/close-binlog-stream stream)

  (count @out-events)
  (take 10 (drop 10 @out-events))

  )


