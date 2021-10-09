(ns funswiss.leihs-sync.zabbix-sender
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.java.shell :as shell :refer [sh]]
    [clojure.pprint :refer [pprint]]
    [clojure.set :as set]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [funswiss.leihs-sync.utils.core :refer [keyword presence str get!]]
    [logbug.thrown :as thrown]
    [taoensso.timbre :as logging :refer [debug info spy]]
    [taoensso.timbre.tools.logging]))


(def prefix-key :zabbix-sender)

(def key-param-key :key-param)
(def binary-path-key :binary-path)
(def config-file-key :config-file)

(def enabled-key :enabled)

(def config-defaults
  (sorted-map
    binary-path-key "zabbix_sender"
    config-file-key "/etc/zabbix/zabbix_agent2.conf"
    key-param-key "foo-bar"
    enabled-key false))

(defn send-success [config state]
  (def ^:dynamic *config* config)
  (def ^:dynamic *state* state)
  (let [config *config*
        state *state* ]
    (when (get-in config [prefix-key enabled-key])
      (let [param-key (get-in config [prefix-key key-param-key])
            in (-> state
                   (select-keys [:groups-created-count
                                 :groups-deleted-count
                                 :groups-updated-count
                                 :groups-users-updated-count
                                 :users-created-count
                                 :users-deleted-count
                                 :users-disabled-count
                                 :users-updated-count
                                 :users-total-disabled-count
                                 :users-total-enabled-count])
                   (->> (map (fn [[k v]]
                               (str "- " "leihs-sync." k "[" param-key "] " (get state k) )))
                        (string/join "\n")))
            cmd [(get-in config [prefix-key binary-path-key])
                 "-c" (get-in config [prefix-key config-file-key])
                 "-i" "-"
                 :in in]
            {:keys [exit out err]} (apply sh cmd)]
        (logging/info exit out err cmd)))))
