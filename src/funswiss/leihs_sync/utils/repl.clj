(ns funswiss.leihs-sync.utils.repl
  (:refer-clojure :exclude [str keyword])
  (:require
    [clj-yaml.core :as yaml]
    [clojure.java.io :as io]
    [clojure.tools.logging :as logging]
    [environ.core :refer [env]]
    [funswiss.leihs-sync.utils.cli-options :refer [long-opt-for-key]]
    [funswiss.leihs-sync.utils.core :refer [keyword presence str get!]]
    [nrepl.server :as nrepl :refer [start-server stop-server]]))


;;; cli-options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce repl-config* (atom nil))

(def prefix-key :repl)

(def repl-enable-key :enabled)
(def repl-port-key :port)
(def repl-bind-key :bind)
(def repl-port-file-key :port-file)

(def config-defaults
  {repl-enable-key false
   repl-port-key (+ 10000 (rand-int (- 65536 10000)))
   repl-bind-key "localhost"
   repl-port-file-key ".nrepl-port" })


;;; server ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce server* (atom nil))

(defn stop []
  (when @server*
    (logging/info "stopping nREPL server " @server*)
    (stop-server @server*)
    (when-let [port-file (repl-port-file-key @repl-config*)]
      (io/delete-file port-file true))
    (reset! server* nil)
    (reset! repl-config* nil)))

(defn init [config]
  (reset! repl-config* (get! config :repl))
  (stop)
  (when (repl-enable-key @repl-config*)
    (logging/info "starting nREPL server " @repl-config*)
    (reset! server*
            (start-server
              :bind (repl-bind-key @repl-config*)
              :port (repl-port-key @repl-config*)))
    (when-let [port-file (and (repl-enable-key @repl-config*) (repl-port-file-key @repl-config*))]
      (spit port-file (str (repl-port-key @repl-config*))))
    (logging/info "started nREPL server ")))
