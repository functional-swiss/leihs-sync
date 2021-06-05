(ns funswiss.leihs-sync.ms.auth
  (:refer-clojure :exclude [str keyword])
  (:require
    [clj-http.client :as http-client]
    [clojure.core.memoize :as memoize]
    [clojure.tools.logging :as logging]
    [funswiss.leihs-sync.utils.cli-options :as cli-opts]
    [funswiss.leihs-sync.utils.core :refer [keyword presence str get! get-in!]]
    [taoensso.timbre :as timbre :refer [debug info]]
    ))

(def CACHE-TIME-SECS 3558)

;;; options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def prefix-key :ms)

(def client-id-key :client-id)
(def client-id-keys [prefix-key client-id-key])

(def client-secret-key :client-secret)
(def client-secret-keys [prefix-key client-secret-key])

(def tenant-id-key :tenant-id)
(def tenant-id-keys [prefix-key tenant-id-key])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-token-uncached [config]
  (->> {:form-params {:client_id (get-in! config client-id-keys)
                      :scope "https://graph.microsoft.com/.default"
                      :client_secret (get-in! config client-secret-keys)
                      :grant_type "client_credentials"}
        :as :json
        :accept :json}
       (http-client/post
         (str "https://login.microsoftonline.com/"
              (get-in! config tenant-id-keys)
              "/oauth2/v2.0/token"))
       :body))

(def get-token-cached
  (memoize/ttl get-token-uncached
               :ttl/threshold (* 1000 CACHE-TIME-SECS)))

(defn assert-valid-expiration-caching! [token]
  (doseq [expires-key [:expires_in :ext_expires_in]]
    (or (some-> token (get expires-key)
                (> CACHE-TIME-SECS))
        (throw (ex-info "PROGRAM ERROR: expiration is smaller than predefined CACHE-TIME-SECS "
                        {:expires-key expires-key
                         :value (get token expires-key)
                         :CACHE-TIME-SECS CACHE-TIME-SECS}))))
  token)

(defn get-token [config]
  (-> config
      get-token-cached
      assert-valid-expiration-caching!))


(defn add-auth-header [request config]
  (-> request
      (assoc-in [:headers "Authorization"]
                (str "Bearer " (-> config get-token :access_token)))))
