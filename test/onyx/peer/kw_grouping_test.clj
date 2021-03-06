(ns onyx.peer.kw-grouping-test
  (:require [midje.sweet :refer :all]
            [onyx.queue.hornetq-utils :as hq-util]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.api]
            [taoensso.timbre :refer [info]]))

(def output (atom []))

(def config (read-string (slurp (clojure.java.io/resource "test-config.edn"))))

(def hq-config {"host" (:host (:non-clustered (:hornetq config)))
                "port" (:port (:non-clustered (:hornetq config)))})

(def id (str (java.util.UUID/randomUUID)))

(def in-queue (str (java.util.UUID/randomUUID)))

(def out-queue (str (java.util.UUID/randomUUID)))

(def coord-opts
  {:hornetq/mode :udp
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
   :onyx.coordinator/revoke-delay 5000})

(def peer-opts
  {:hornetq/mode :udp
   :hornetq.udp/cluster-name (:cluster-name (:hornetq config))
   :hornetq.udp/group-address (:group-address (:hornetq config))
   :hornetq.udp/group-port (:group-port (:hornetq config))
   :hornetq.udp/refresh-timeout (:refresh-timeout (:hornetq config))
   :hornetq.udp/discovery-timeout (:discovery-timeout (:hornetq config))
   :zookeeper/address (:address (:zookeeper config))
   :onyx/id id})

(def conn (onyx.api/connect :memory coord-opts))

(hq-util/create-queue! hq-config in-queue)
(hq-util/create-queue! hq-config out-queue)

(defmethod l-ext/inject-lifecycle-resources
  :onyx.peer.kw-grouping-test/sum-balance
  [_ event]
  (let [balance (atom {})]
    {:onyx.core/params [balance]
     :test/balance balance}))

(defmethod l-ext/close-lifecycle-resources
  :onyx.peer.kw-grouping-test/sum-balance
  [_ {:keys [test/balance]}]
  (swap! output conj @balance)
  {})

(defn sum-balance [state {:keys [name amount] :as segment}]
  (swap! state (fn [v] (assoc v name (+ (get v name 0) amount))))
  [])

(def workflow {:in {:sum-balance :out}})

(def catalog
  [{:onyx/name :in
    :onyx/ident :hornetq/read-segments
    :onyx/type :input
    :onyx/medium :hornetq    
    :onyx/consumption :concurrent
    :hornetq/queue-name in-queue
    :hornetq/host (:host (:non-clustered (:hornetq config)))
    :hornetq/port (:port (:non-clustered (:hornetq config)))
    :onyx/batch-size 1000}

   {:onyx/name :sum-balance
    :onyx/ident :onyx.peer.kw-grouping-test/sum-balance
    :onyx/fn :onyx.peer.kw-grouping-test/sum-balance
    :onyx/type :function
    :onyx/group-by-key :name
    :onyx/consumption :concurrent
    :onyx/batch-size 1000}
   
   {:onyx/name :out
    :onyx/ident :hornetq/write-segments
    :onyx/type :output
    :onyx/medium :hornetq
    :onyx/consumption :concurrent
    :hornetq/queue-name out-queue
    :hornetq/host (:host (:non-clustered (:hornetq config)))
    :hornetq/port (:port (:non-clustered (:hornetq config)))
    :onyx/batch-size 1000}])

(def size 3000)

(def data
  (concat
   (map (fn [_] {:name "Mike" :amount 10}) (range size))
   (map (fn [_] {:name "Dorrene" :amount 10}) (range size))
   (map (fn [_] {:name "Benti" :amount 10}) (range size))
   (map (fn [_] {:name "John" :amount 10}) (range size))
   (map (fn [_] {:name "Shannon" :amount 10}) (range size))
   (map (fn [_] {:name "Kristen" :amount 10}) (range size))
   (map (fn [_] {:name "Benti" :amount 10}) (range size))
   (map (fn [_] {:name "Mike" :amount 10}) (range size))
   (map (fn [_] {:name "Steven" :amount 10}) (range size))
   (map (fn [_] {:name "Dorrene" :amount 10}) (range size))
   (map (fn [_] {:name "John" :amount 10}) (range size))
   (map (fn [_] {:name "Shannon" :amount 10}) (range size))
   (map (fn [_] {:name "Santana" :amount 10}) (range size))
   (map (fn [_] {:name "Roselyn" :amount 10}) (range size))
   (map (fn [_] {:name "Krista" :amount 10}) (range size))
   (map (fn [_] {:name "Starla" :amount 10}) (range size))
   (map (fn [_] {:name "Derick" :amount 10}) (range size))
   (map (fn [_] {:name "Orlando" :amount 10}) (range size))
   (map (fn [_] {:name "Rupert" :amount 10}) (range size))
   (map (fn [_] {:name "Kareem" :amount 10}) (range size))
   (map (fn [_] {:name "Lesli" :amount 10}) (range size))
   (map (fn [_] {:name "Carol" :amount 10}) (range size))
   (map (fn [_] {:name "Willie" :amount 10}) (range size))
   (map (fn [_] {:name "Noriko" :amount 10}) (range size))
   (map (fn [_] {:name "Corine" :amount 10}) (range size))
   (map (fn [_] {:name "Leandra" :amount 10}) (range size))
   (map (fn [_] {:name "Chadwick" :amount 10}) (range size))
   (map (fn [_] {:name "Teressa" :amount 10}) (range size))
   (map (fn [_] {:name "Tijuana" :amount 10}) (range size))
   (map (fn [_] {:name "Verna" :amount 10}) (range size))
   (map (fn [_] {:name "Alona" :amount 10}) (range size))
   (map (fn [_] {:name "Wilson" :amount 10}) (range size))
   (map (fn [_] {:name "Carly" :amount 10}) (range size))
   (map (fn [_] {:name "Nubia" :amount 10}) (range size))
   (map (fn [_] {:name "Hollie" :amount 10}) (range size))
   (map (fn [_] {:name "Allison" :amount 10}) (range size))
   (map (fn [_] {:name "Edwin" :amount 10}) (range size))
   (map (fn [_] {:name "Zola" :amount 10}) (range size))
   (map (fn [_] {:name "Britany" :amount 10}) (range size))
   (map (fn [_] {:name "Courtney" :amount 10}) (range size))
   (map (fn [_] {:name "Mathew" :amount 10}) (range size))
   (map (fn [_] {:name "Luz" :amount 10}) (range size))
   (map (fn [_] {:name "Tyesha" :amount 10}) (range size))
   (map (fn [_] {:name "Eusebia" :amount 10}) (range size))
   (map (fn [_] {:name "Fletcher" :amount 10}) (range size))))

(hq-util/write-and-cap! hq-config in-queue data 100)

(def v-peers (onyx.api/start-peers conn 6 peer-opts))

(onyx.api/submit-job conn {:catalog catalog :workflow workflow})

(def results (hq-util/consume-queue! hq-config out-queue 1))

(doseq [v-peer v-peers]
  (try
    ((:shutdown-fn v-peer))
    (catch Exception e (prn e))))

(try
  (onyx.api/shutdown conn)
  (catch Exception e (prn e)))

(def out-val @output)

;;; Scan the key set, dropping any nils. Count the distinct keys.
;;; Do the same for the right hand side of the expression, but turn it into a set.
;;; If there's the same number of elements, then the grouping was mutually exclusive.
(fact (count (filter identity (mapcat keys out-val))) =>
      (count (into #{} (filter identity (mapcat keys out-val)))))

(fact results => [:done])

