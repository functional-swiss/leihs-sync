(ns zhdk.prtg
  (:refer-clojure :exclude [str keyword])
  (:require
    [cheshire.core :as cheshire]
    [clj-http.client :as http-client]
    [funswiss.leihs-sync.utils.core :refer [keyword presence str get! get-in!]]
    [logbug.catcher :as catcher]
    [logbug.thrown :as thrown]
    [taoensso.timbre :as logging]
    ))

(defn post [prtg-url msg]
  (logging/info 'prtg-msg msg)
  (http-client/post
    prtg-url
    {:accept :json
     :content-type :json
     :as :json
     :body
     (cheshire/generate-string msg)}))

(defn send-success [prtg-url state]
  (let [msg {:prtg
             {:result
              (->> [:groups-added-count
                    :groups-deleted-count
                    :groups-updated-count
                    :groups-users-updated-count
                    :users-created-count
                    :users-deleted-count
                    :users-disabled-count
                    :users-photos-checked
                    :users-photos-updated
                    :users-updated-count]
                   (map (fn [kw]
                          {:channel kw
                           :unit "Count"
                           :value (get! state kw)})))}}]
    (logging/info "send-success" msg)
    (post prtg-url msg)))

(defn send-error [prtg-url ex]
  (let [msg {:prtg
             {:error 1
              :text (thrown/stringify ex)}}]
    (post prtg-url msg)))
