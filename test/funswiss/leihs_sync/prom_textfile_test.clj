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

(deftest render-escapes-label-values
  (let [out (prom/render "a\\b\"c\nd" {:users-created-count 1} 1)]
    (is (re-find #"(?m)^leihs_sync_users_created_count\{job=\"a\\\\b\\\"c\\nd\"\} 1$" out))))

(deftest write!-creates-world-readable-file-and-no-tmp-leftovers
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "prom-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        target (java.io.File. dir "leihs-functional-sync-counts.prom")]
    (prom/write! (.getPath target) "leihs-functional-sync" state)
    (is (.exists target))
    (is (re-find #"leihs_sync_users_created_count" (slurp target)))
    (is (.canRead target)) ; readable
    (is (= 1 (count (.listFiles dir)))))) ; tmp file cleaned up

(deftest send-success-skips-when-env-unset
  ;; PROM_TEXTFILE_PATH is not set in the test environment
  (is (nil? (prom/send-success state))))
