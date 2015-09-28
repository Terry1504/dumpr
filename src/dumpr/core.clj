(ns dumpr.core
  "Dumpr API"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.core.async :as async :refer [chan >!!]]
            [schema.core :as s]
            [taoensso.timbre :as log]
            [dumpr.query :as query]
            [dumpr.table-schema :as table-schema]
            [dumpr.events :as events]
            [dumpr.stream :as stream]
            [dumpr.binlog :as binlog]
            [dumpr.row-format :as row-format]))

(def load-buffer-default-size 1000)
(def stream-buffer-default-size 50)


;; Public API
;;

(defn create-conf
  "Create configuration needed by stream and table load.
  Takes two params:

  :conn-params Map that contains the following keys:
               :user, :password, :host, :port, :db and :server-id
  :id-fns      Maps table name (key) to function (value) that returns the
               identifier value for that table row. Normally you'll be using
               the identifier column as a keyword as the id function
               (e.g. {:mytable :identifier})"
  [conn-params id-fns]
  {:db-spec (query/db-spec conn-params)
   :conn-params conn-params
   :id-fns id-fns})

(defn load-tables
  "Load the contents of given tables from the DB and return the
  results as :upsert rows via out channel. Tables are given as a
  vector of table keywords. Keyword is mapped to table name using
  (name table-kw). Loading happens in the order that tables were
  given. Results are returned strictly in the order that tables were
  given. Out channel can be specified if specific buffer size or
  behavior is desired. Result map has following fields:

  :out        The out chan for result rows. Closed when
              all tabels are loaded
  :binlog-pos Binlog position *before* table load started.
              Use this to start binlog consuming."
  ([tables conf] (load-tables tables conf (chan load-buffer-default-size)))
  ([tables {:keys [db-spec conn-params id-fns]} out]
   (let [db          (:db conn-params)
         binlog-pos  (query/binlog-position db-spec)
         tables-ch   (chan 0)
         table-specs (chan 0)
         schema-chan (table-schema/load-and-parse-schemas
                      tables db-spec db id-fns)]

     (async/pipeline-async 1
                           out
                           (partial query/stream-table db-spec)
                           schema-chan)
     {:out out
      :binlog-pos binlog-pos})))

(defn valid-binlog-pos?
  "Validate that the given binlog position is available at the DB
  server and streaming can be started from this position.

  Note! The validation is not perfect. It will check the given
  position against available files and their max positions but cannot
  tell if a position is in the middle of an event. In practice this
  never occurs when the continue position is fetched from an event
  produced by the lib."
  [conf binlog-pos]
  (let [db-spec                 (:db-spec conf)
        {:keys [file position]} binlog-pos
        valid-positions         (query/show-binlog-positions db-spec)]
    (->> valid-positions
         (some (fn [{:keys [log_name file_size]}]
                 (and (= log_name file)
                      (<= position file_size))))
         boolean)))

(defn- validate-binlog-pos! [conf binlog-pos]
  (when-not (valid-binlog-pos? conf binlog-pos)
    (throw (ex-info "Invalid binary log position."
                    {:binlog-pos binlog-pos}))))

(defn binlog-stream
  ([conf binlog-pos]
   (binlog-stream conf binlog-pos nil (chan stream-buffer-default-size)))

  ([conf binlog-pos only-tables]
   (binlog-stream conf binlog-pos only-tables (chan stream-buffer-default-size)))

  ([conf binlog-pos only-tables out]
   (validate-binlog-pos! conf binlog-pos)
   (let [db-spec            (:db-spec conf)
         db                 (get-in conf [:conn-params :db])
         id-fns             (:id-fns conf)
         keepalive-interval (get-in conf [:conn-params :query-max-keepalive-interval])
         schema-cache       (atom {})
         events-xform       (comp (map events/parse-event)
                                  (remove nil?)
                                  stream/filter-txs
                                  (stream/add-binlog-filename (:filename binlog-pos))
                                  stream/group-table-maps
                                  (stream/filter-database db)
                                  (stream/filter-tables (set only-tables)))
         events-ch          (chan 1 events-xform)
         schema-loaded-ch   (chan 1)
         stopped            (atom false)
         client             (binlog/new-binlog-client (:conn-params conf)
                                                      binlog-pos
                                                      events-ch)]

     (stream/add-table-schema schema-loaded-ch
                              events-ch
                              {:schema-cache schema-cache
                               :db-spec db-spec
                               :id-fns id-fns
                               :keepalive-interval keepalive-interval
                               :stopped stopped})
     (async/pipeline 4
                     out
                     (comp (map stream/convert-with-schema)
                           cat)
                     schema-loaded-ch)
     {:client client
      :out out
      :stopped stopped})))


(defn start-binlog-stream [stream]
  (binlog/start-client (:client stream)))

(defn close-binlog-stream [stream]
  (reset! (:stopped stream) true)
  (binlog/stop-client (:client stream)))
