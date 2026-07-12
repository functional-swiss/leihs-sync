(ns funswiss.leihs-sync.prom-textfile
  (:refer-clojure :exclude [str])
  (:require
   [clojure.string :as string]
   [funswiss.leihs-sync.utils.core :refer [str]]))

(def count-keys
  ;; keep in sync with zabbix-sender/send-success select-keys
  [:groups-created-count
   :groups-deleted-count
   :groups-updated-count
   :groups-users-updated-count
   :users-created-count
   :users-deleted-count
   :users-disabled-count
   :users-updated-count
   :users-total-disabled-count
   :users-total-enabled-count])

(defn- metric-name [k]
  (str "leihs_sync_" (string/replace (name k) "-" "_")))

(defn- escape-label [s]
  (-> s (string/replace "\\" "\\\\") (string/replace "\"" "\\\"")))

(defn render
  "Prometheus text exposition of the sync-count gauges plus a
  last-success timestamp. Emits only keys present in state."
  [job-label state epoch-seconds]
  (let [job (escape-label job-label)
        line (fn [n v] (str "# TYPE " n " gauge\n"
                            n "{job=\"" job "\"} " v "\n"))]
    (str (->> count-keys
              (filter #(contains? state %))
              (map #(line (metric-name %) (get state %)))
              (apply str))
         (line "leihs_sync_last_success_timestamp_seconds" epoch-seconds))))
