(ns funswiss.aad-leihs-sync.logging
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.tools.logging :as logging]
    [environ.core :refer [env]]
    [funswiss.aad-leihs-sync.utils.cli-options :refer [long-opt-for-key]]
    [funswiss.aad-leihs-sync.utils.core :refer [keyword presence str]]
    [taoensso.timbre :as timbre :refer [debug info]]
    [taoensso.timbre.appenders.core :as appenders]
    [taoensso.timbre.tools.logging]
    ))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce options* (atom nil))

(def logging-config-file-key :logging-config-file)
(def options-keys [logging-config-file-key])

(defn option-specs []
  [["-l" (long-opt-for-key logging-config-file-key)
    "Additional configuration file(s) for logging. See also https://github.com/ptaoussanis/timbre#configuration."
    :multi true
    :default (or (some->> logging-config-file-key env (conj [])) [])
    :update-fn conj]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(taoensso.timbre.tools.logging/use-timbre)

(def config-defaults
  {:min-level :info})

(timbre/merge-config! config-defaults)

(defn init [all-options]
  (reset! options* (select-keys all-options options-keys))
  (info "initializing logging " @options*)
  (doseq [configfile (:logging-config-file @options*)]
    (info 'configfile configfile)
    (when-let [content (slurp configfile)]
      (timbre/merge-config! (read-string content))))
  (info "initialized logging " 'timbre/*config* " " (pr-str timbre/*config*)))
