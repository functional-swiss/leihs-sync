(ns funswiss.aad-leihs-sync.ms.auth
  (:refer-clojure :exclude [str keyword])
  (:require
    [clj-http.client :as http-client]
    [clojure.core.memoize :as memoize]
    [clojure.tools.logging :as logging]
    [funswiss.aad-leihs-sync.utils.cli-options :as cli-opts]
    [funswiss.aad-leihs-sync.utils.core :refer [keyword presence str]]
    [funswiss.aad-leihs-sync.utils.obscurity :as obscurity]
    [taoensso.timbre :as timbre :refer [debug info]]
    ))

(def CACHE-TIME-SECS 3558)


;;; options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def client-id-key :ms-client-id)
(def client-secret-key :ms-client-secret)
(def tennant-id-key :ms-tennat-id)

(defn options-specs []
  [[nil (cli-opts/long-opt-for-key tennant-id-key) "MS tennant-id, id of the oranization"
    :default (cli-opts/default tennant-id-key)]
   [nil (cli-opts/long-opt-for-key client-id-key) "MS client-id, id of the client application"
    :default (cli-opts/default client-id-key)]
   [nil (cli-opts/long-opt-for-key client-secret-key) "MS client-secret, some secret for the client"
    :default (some-> (cli-opts/default client-secret-key) obscurity/encrypt)
    :parse-fn #(some-> % obscurity/encrypt)]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-token-uncached [opts]
  (->> {:form-params {:client_id (client-id-key opts)
                      :scope "https://graph.microsoft.com/.default"
                      :client_secret (-> opts client-secret-key obscurity/decrypt)
                      :grant_type "client_credentials"}
        :as :json
        :accept :json}
       (http-client/post
         (str "https://login.microsoftonline.com/"
              (tennant-id-key opts)
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

(defn get-token [opts]
  (-> opts
      get-token-cached
      assert-valid-expiration-caching!))


(defn add-auth-header [request opts]
  (-> request
      (assoc-in [:headers "Authorization"]
                (str "Bearer " (-> opts get-token :access_token)))))
