(ns onyx.coordinator.log.datomic
  (:require [com.stuartsierra.component :as component]
            [onyx.coordinator.extensions :as extensions]
            [onyx.util :as u]
            [datomic.api :as d]))

(defn log-schema []
  (let [resource (clojure.java.io/resource "datomic-schema.edn")]
    (read-string (slurp resource))))

(defn schema-set? [conn]
  (seq (d/q '[:find ?e :where [?e :db/ident :node/peer]] (d/db conn))))

(defn start-datomic! [uri schema]
  (d/create-database uri)
  (let [conn (d/connect uri)]
    (when-not (schema-set? conn)
      @(d/transact conn schema))
    conn))

(defrecord Datomic [uri schema]
  component/Lifecycle

  (start [component]
    (prn "Starting Datomic")
    (let [conn (start-datomic! uri schema)]
      (assoc component :conn conn)))

  (stop [component]
    (prn "Stopping Datomic")
    (d/delete-database uri)
    (d/shutdown false)
    component))

(defn datomic [uri schema]
  (map->Datomic {:uri uri :schema schema}))

(defn create-job-datom [catalog workflow tasks]
  {:db/id (d/tempid :onyx/log)
   :job/id (d/squuid)
   :job/catalog (pr-str catalog)
   :job/workflow (pr-str workflow)
   :job/task tasks})

(defn create-task-datom [task]
  {:db/id (d/tempid :onyx/log)
   :task/name (:name task)
   :task/phase (:phase task)
   :task/complete? false
   :task/ingress-queues (:ingress-queues task)
   :task/egress-queues (or (vals (:egress-queues task)) [])})

(defn find-incomplete-tasks [db]
  (let [query '[:find ?task :where [?task :task/complete? false]]]
    (d/q query db)))

(defn find-active-task-ids [db tasks]
  (let [query '[:find ?task :in $ ?task :where [?peer :peer/task ?task]]]
    (into #{} (mapcat #(first (d/q query db (:db/id %))) tasks))))

(defn sort-tasks-by-phase [db tasks]
  (sort-by :task/phase (map (fn [[t]] (d/entity db t)) tasks)))

(defn next-essential-task [db]
  (let [incomplete-tasks (find-incomplete-tasks db)
        sorted-tasks (sort-tasks-by-phase db incomplete-tasks)
        active-tasks (find-active-task-ids db sorted-tasks)]
    (first (filter (fn [t] (not (contains? active-tasks (:db/id t)))) sorted-tasks))))

(defmethod extensions/mark-peer-born Datomic
  [log place]
  (let [tx [[:onyx.fn/add-peer (d/tempid :onyx/log) :idle place]]]
    @(d/transact (:conn log) tx)))

(defmethod extensions/mark-peer-dead Datomic
  [log place]
  (let [tx [[:onyx.fn/remove-peer place]]]
    @(d/transact (:conn log) tx)))

(defmethod extensions/plan-job Datomic
  [log catalog workflow tasks]
  (let [task-datoms (map create-task-datom tasks)
        job-datom (create-job-datom catalog workflow task-datoms)]
    @(d/transact (:conn log) [job-datom])
    (:job/id job-datom)))

(defmethod extensions/next-task Datomic
  [log]
  (let [db (d/db (:conn log))]
    (next-essential-task db)))

(defmethod extensions/idle-peer Datomic
  [log]
  (let [db (d/db (:conn log))
        peers (d/q '[:find ?place :where
                     [?peer :peer/status :idle]
                     [?peer :node/peer ?place]] db)]
    (ffirst peers)))

(defmethod extensions/mark-offered Datomic
  [log task peer nodes]
  (let [tx [[:onyx.fn/offer-task task peer nodes]]]
    @(d/transact (:conn log) tx)))

(defmethod extensions/ack Datomic
  [log ack-place]
  (let [tx [[:onyx.fn/ack-task ack-place]]]
    @(d/transact (:conn log) tx)))

(defmethod extensions/evict Datomic
  [log task])

(defmethod extensions/complete Datomic
  [log complete-place]
  (let [tx [[:onyx.fn/complete-task complete-place]]]    
    @(d/transact (:conn log) tx)))
