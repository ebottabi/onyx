(ns onyx.coordinator.sim-test-utils
  (:require [midje.sweet :refer :all]
            [clojure.core.async :refer [chan <!! >!! timeout]]
            [clojure.data.generators :as gen]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [zookeeper :as zk]
            [taoensso.timbre :refer [info]]
            [onyx.coordinator.impl :as impl]
            [onyx.extensions :as extensions]
            [onyx.system :refer [onyx-coordinator]]))

(def config (read-string (slurp (clojure.java.io/resource "test-config.edn"))))

(defn with-system [f & opts]
  (let [id (str (java.util.UUID/randomUUID))
        defaults {:hornetq/mode :udp
                  :hornetq/server? true
                  :hornetq.udp/cluster-name (:cluster-name (:hornetq config))
                  :hornetq.udp/group-address (:group-address (:hornetq config))
                  :hornetq.udp/group-port (:group-port (:hornetq config))
                  :hornetq.udp/refresh-timeout (:refresh-timeout (:hornetq config))
                  :hornetq.udp/discovery-timeout (:discovery-timeout (:hornetq config))
                  :hornetq.server/type :embedded
                  :hornetq.embedded/config (:configs (:hornetq config))
                  :zookeeper/address (:address (:zookeeper config))
                  :zookeeper/server? true
                  :zookeeper.server/port (:spawn-port (:zookeeper config))                  
                  :onyx/id id
                  :onyx.coordinator/revoke-delay 4000}]
    (let [system (onyx-coordinator (apply merge defaults opts))
          live (component/start system)
          coordinator (:coordinator live)
          sync (:sync live)]
      (try
        (f coordinator sync)
        (finally
         (component/stop live))))))

(defn reset-conn
  "Reset connection to a scratch database. Use memory database if no
   URL passed in."
  ([]
     (reset-conn (str "datomic:mem://" (d/squuid))))
  ([uri]
     (d/delete-database uri)
     (d/create-database uri)
     (d/connect uri)))

(defn load-schema
  [conn resource]
  (let [m (-> resource clojure.java.io/resource slurp read-string)]
    (doseq [v (vals m)]
      (doseq [tx v]
        (d/transact conn tx)))))

(defn task-completeness [sync]
  (let [job-nodes (extensions/bucket sync :job)
        task-paths (map #(extensions/resolve-node sync :task %) job-nodes)]
    (doseq [task-path task-paths]
      (doseq [task-node (extensions/children sync task-path)]
        (when-not (impl/metadata-task? task-node)
          (fact (impl/task-complete? sync task-node) => true))))))

(defn peer-stack-sequential-ranges
  ([sync state-stack-nodes]
     (peer-stack-sequential-ranges
      sync
      (drop-while #(not= (:state (extensions/read-node sync %)) :acking) state-stack-nodes)
      []
      nil))
  ([sync [node & nodes :as stack] ranges start]
     (cond (not (seq stack)) ranges
           (nil? start) (if (= (:state (extensions/read-node sync node)) :acking)
                          (recur sync nodes ranges node)
                          (recur sync nodes ranges nil))
           :else
           (if (some #{(:state (extensions/read-node sync node))} #{:idle :revoked :dead})
             (recur sync nodes (conj ranges [start node]) nil)
             (recur sync nodes ranges start)))))

(defn sequential-safety [sync]
  (let [paths (extensions/bucket sync :peer-state)]
    (doseq [state-path paths]
      (let [states (sort (extensions/children sync state-path))
            node-pairs (peer-stack-sequential-ranges sync states)
            range-nodes (map (fn [[a b]]
                               [(:czxid (:stat (zk/data (:conn sync) a)))
                                (:czxid (:stat (zk/data (:conn sync) b)))
                                (:task-node (extensions/read-node sync a))])
                             node-pairs)
            other-peers (remove (partial = state-path) paths)]
        (doseq [other-peer other-peers]
          (doseq [range-node range-nodes]
            (doseq [state (sort (extensions/children sync other-peer))]
              (let [status (extensions/read-node sync state)]
                (when (= (:task-node status) (nth range-node 2))
                  (let [zxid (:czxid (:stat (zk/data (:conn sync) state)))]
                    (when-not (= (:state status) :dead)
                      (fact (or (< zxid (first range-node))
                                (> zxid (second range-node))) => true))))))))))))

(defn peer-liveness [sync]
  (doseq [state-path (extensions/bucket sync :peer-state)]
    (let [states (extensions/children sync state-path)
          state-data (map (partial extensions/read-node sync) states)
          active-states (filter #(= (:state %) :active) state-data)]
      (fact (count active-states) =not=> zero?))))

(defn peer-fairness [sync n-peers n-jobs tasks-per-job]
  (let [state-paths (extensions/bucket sync :peer-state)
        state-seqs (map (partial extensions/children sync) state-paths)
        state-seqs-data (map #(map (partial extensions/read-node sync) %) state-seqs)
        n-tasks (map #(count (filter (fn [x] (= (:state x) :active)) %)) state-seqs-data)
        mean (/ (* n-jobs tasks-per-job) n-peers)
        confidence 0.75]

    (fact "All peers got within 75% of the average number of tasks"
          (every?
           #(and (<= (- mean (* mean confidence)) %)
                 (>= (+ mean (* mean confidence)) %))
           n-tasks)) => true))

(def legal-transitions
  {:idle #{:idle :acking :dead}
   :acking #{:acking :active :revoked :dead}
   :active #{:active :waiting :sealing :idle :dead}
   :waiting #{:waiting :sealing :idle :dead}
   :sealing #{:sealing :idle :dead}
   :revoked #{:revoked :dead}
   :dead #{:dead}})

(defn peer-state-transition-correctness [sync]
  (doseq [state-path (extensions/bucket sync :peer-state)]
    (let [states (extensions/children sync state-path)
          sorted-states (sort states)
          state-data (map (partial extensions/read-node sync) sorted-states)]
      (dorun
       (map-indexed
        (fn [i state]
          (when (< i (dec (count state-data)))
            (let [current-state (:state state)
                  next-state (:state (nth state-data (inc i)))]
              (when-not (fact (some #{next-state}
                                    (get legal-transitions current-state))
                              =not=> nil?)
                (prn current-state "->" next-state)))))
        state-data)))))

(defn create-peer [model components peer]
  (future
    (try
      (let [coordinator (:coordinator components)
            sync (:sync components)
            payload (extensions/create sync :payload)
            pulse (extensions/create sync :pulse)
            shutdown (extensions/create sync :shutdown)
            sync-spy (chan 1)
            status-spy (chan 1)
            seal-spy (chan 1)]
        
        (extensions/write-node sync (:node peer)
                                {:id (:uuid peer)
                                 :peer-node (:node peer)
                                 :pulse-node (:node pulse)
                                 :shutdown-node (:node shutdown)
                                 :payload-node (:node payload)})
        (extensions/on-change sync (:node payload) #(>!! sync-spy %))
        (extensions/create sync :born-log (:node peer))
        
        (>!! (:born-peer-ch-head coordinator) true)

        (loop [p payload]
          (<!! sync-spy)
          (<!! (timeout (gen/geometric (/ 1 (:model/mean-ack-time model)))))

          (let [nodes (:nodes (extensions/read-node sync (:node p)))]
            (extensions/on-change sync (:node/status nodes) #(>!! status-spy %))
            (extensions/touch-node sync (:node/ack nodes))
            (<!! status-spy)

            (<!! (timeout (gen/geometric (/ 1 (:model/mean-completion-time model)))))

            (let [next-payload (extensions/create sync :payload)]
              (extensions/write-node sync (:node peer) {:id (:uuid peer)
                                                        :peer-node (:node peer)
                                                        :pulse-node (:node pulse)
                                                        :shutdown-node (:node shutdown)
                                                        :payload-node (:node next-payload)})

              (extensions/on-change sync (:node/seal nodes) #(>!! seal-spy %))
              (extensions/touch-node sync (:node/exhaust nodes))
              (<!! seal-spy)
              
              (extensions/on-change sync (:node next-payload) #(>!! sync-spy %))
              (extensions/touch-node sync (:node/completion nodes))

              (recur next-payload)))))
      (catch InterruptedException e
        (info "Peer intentionally killed"))
      (catch Exception e
        (.printStackTrace e)))))

(defn create-peers! [model components cluster]
  (doseq [_ (range (:model/n-peers model))]
    (let [peer (extensions/create (:sync components) :peer)]
      (swap! cluster assoc peer (create-peer model components peer)))))

