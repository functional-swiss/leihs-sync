(ns funswiss.leihs-sync.sync.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [funswiss.leihs-sync.sync.core :as core])
  (:import
   [clojure.lang ExceptionInfo]))

(def leihs-user {:org_id "org-1" :id "leihs-1"})

(defn- reset-state! []
  (reset! core/state* core/initial-state))

(deftest tolerates-transient-5xx
  (doseq [status core/transient-photo-http-statuses]
    (testing (str "status " status " is swallowed and counted")
      (reset-state!)
      (with-redefs [core/check-and-update-image
                    (fn [_] (throw (ex-info "boom" {:status status})))]
        (is (nil? (core/check-and-update-image-tolerant leihs-user)))
        (is (= 1 (:users-photos-failed @core/state*)))))))

(deftest rethrows-non-transient-http-error
  (reset-state!)
  (with-redefs [core/check-and-update-image
                (fn [_] (throw (ex-info "not found" {:status 400})))]
    (is (thrown? ExceptionInfo (core/check-and-update-image-tolerant leihs-user)))
    (is (= 0 (:users-photos-failed @core/state*)))))

(deftest rethrows-unscalable-image
  (reset-state!)
  (with-redefs [core/check-and-update-image
                (fn [_] (throw (ex-info "unscalable image " {})))]
    (is (thrown? ExceptionInfo (core/check-and-update-image-tolerant leihs-user)))
    (is (= 0 (:users-photos-failed @core/state*)))))
