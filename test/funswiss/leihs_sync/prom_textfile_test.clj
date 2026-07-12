(ns funswiss.leihs-sync.prom-textfile-test
  (:require
   [clojure.test :refer [deftest is]]
   [funswiss.leihs-sync.prom-textfile :as prom]))

(def state
  {:users-created-count 3
   :users-total-enabled-count 42
   :irrelevant-key "ignored"})

(deftest render-emits-present-count-keys-with-job-label
  (let [out (prom/render "leihs-functional-sync" state 1752300000)]
    (is (re-find #"(?m)^leihs_sync_users_created_count\{job=\"leihs-functional-sync\"\} 3$" out))
    (is (re-find #"(?m)^leihs_sync_users_total_enabled_count\{job=\"leihs-functional-sync\"\} 42$" out))
    (is (re-find #"(?m)^leihs_sync_last_success_timestamp_seconds\{job=\"leihs-functional-sync\"\} 1752300000$" out))
    (is (re-find #"(?m)^# TYPE leihs_sync_users_created_count gauge$" out))))

(deftest render-omits-absent-keys
  (let [out (prom/render "x" {:users-created-count 0} 1)]
    (is (not (re-find #"users_deleted" out)))
    (is (re-find #"(?m)^leihs_sync_users_created_count\{job=\"x\"\} 0$" out))))
