(ns funswiss.leihs-sync.utils.logging
  (:refer-clojure :exclude [str keyword])
  (:require
   [clojure.tools.logging :as logging]
   [environ.core :refer [env]]
   [funswiss.leihs-sync.utils.cli-options :refer [long-opt-for-key]]
   [funswiss.leihs-sync.utils.core :refer [keyword presence str]]
   [taoensso.timbre :as timbre :refer [debug info]]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre.tools.logging]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce options* (atom nil))

(def logging-config-file-key :logging-config-file)
(def options-keys [logging-config-file-key])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(taoensso.timbre.tools.logging/use-timbre)

(def config-defaults
  {:min-level [[#{;"funswiss.leihs-sync.leihs.core"
                  ;"funswiss.leihs-sync.sync.core"
                  }:debug]
               [#{"funswiss.*" "zhdk.*"} :info]
               [#{"*"} :warn]]})

(timbre/merge-config! config-defaults)

(defn init [all-options]
  (reset! options* (select-keys all-options options-keys))
  (info "initializing logging " @options*)
  (doseq [configfile (:logging-config-file @options*)]
    (info 'configfile configfile)
    (when-let [content (slurp configfile)]
      (timbre/merge-config! (read-string content))))
  (info "initialized logging " 'timbre/*config* " " (pr-str timbre/*config*)))
