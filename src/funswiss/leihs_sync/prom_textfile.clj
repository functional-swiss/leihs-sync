(ns funswiss.leihs-sync.prom-textfile
  (:refer-clojure :exclude [str])
  (:require
   [clojure.string :as string]
   [funswiss.leihs-sync.utils.core :refer [presence str]]
   [taoensso.timbre :as logging])
  (:import
   [java.io File]
   [java.nio.file CopyOption Files StandardCopyOption]))

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
  (-> s
      (string/replace "\\" "\\\\")
      (string/replace "\"" "\\\"")
      (string/replace "\n" "\\n")))

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

(defn write!
  "Atomically writes the rendered exposition to path-str:
  tmp file in the same directory, world-readable, then ATOMIC_MOVE.
  node_exporter (different user, not in our group) must be able to read it."
  [path-str job-label state]
  (let [target (File. path-str)
        tmp (File/createTempFile (.getName target) ".tmp"
                                 (.getParentFile target))]
    (try
      (spit tmp (render job-label state (quot (System/currentTimeMillis) 1000)))
      (.setReadable tmp true false)
      (Files/move
       (.toPath tmp) (.toPath target)
       (into-array CopyOption
                   [StandardCopyOption/ATOMIC_MOVE
                    StandardCopyOption/REPLACE_EXISTING]))
      (finally (.delete tmp)))))

(defn send-success
  "Dual-send lane next to zabbix-sender: writes count gauges to the
  node_exporter textfile collector. Toggled by PROM_TEXTFILE_PATH
  (full target path, set by the systemd unit); unset = disabled.
  Throws on write failure — same contract as zabbix-sender (run is
  marked failed, Failed Systemd Units alert picks it up)."
  [state]
  (if-let [path (presence (System/getenv "PROM_TEXTFILE_PATH"))]
    (let [job (or (presence (System/getenv "PROM_JOB_LABEL"))
                  (-> path File. .getName
                      (string/replace #"-counts\.prom$" "")))]
      (logging/info "prom-textfile: writing " path)
      (write! path job state))
    (logging/info "prom-textfile: skipped, PROM_TEXTFILE_PATH not set")))
