(ns funswiss.leihs-sync.main
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli]
    [clojure.set :as set]
    [funswiss.leihs-sync.utils.cli-options :as cli-opts]
    [clj-yaml.core :as yaml]
    [clojure.string :as string]
    [funswiss.leihs-sync.leihs.core :as leihs-core]
    [funswiss.leihs-sync.ms.core :as ms-core]
    [funswiss.leihs-sync.sync.core :as sync-core]
    [funswiss.leihs-sync.utils.core :refer [deep-merge-limited keyword presence str get! get-in!]]
    [funswiss.leihs-sync.utils.logging :as service-logging]
    [funswiss.leihs-sync.utils.repl :as repl]
    [taoensso.timbre.tools.logging]
    [taoensso.timbre :as logging :refer [debug info spy]]
    [zhdk.zapi.core :as zapi-core])
  (:gen-class))

(def config-file-key :config-file)
(def write-config-file-key :write-config-file)

(def cli-options
  (concat
    [["-h" "--help"]
     ["-d" "--dev" "DEV mode, does not exit"]
     ["-c" (cli-opts/long-opt-for-key config-file-key)
      "YAML configuration file."]
     [nil (cli-opts/long-opt-for-key write-config-file-key)
      (->> [ "Write out the merged configuration from defaults, "
            " config-file and command-line config; then exit."]
           (string/join "\n"))
      :default nil]
     ["-l" (cli-opts/long-opt-for-key service-logging/logging-config-file-key)
      "Additional configuration file(s) for logging. See also https://github.com/ptaoussanis/timbre#configuration."
      :default []
      :update-fn conj]]))

(defn main-usage [options-summary & more]
  (->> ["add-leihs-sync"
        ""
        "usage: add-leihs-sync [<opts>]"
        ""
        ""
        "Options:"
        options-summary
        ""
        ""
        (when more
          ["-------------------------------------------------------------------"
           (with-out-str (pprint more))
           "-------------------------------------------------------------------"])]
       flatten (clojure.string/join \newline)))

(defonce main-options* (atom {}))

(defonce exception* (atom nil))

(def default-config
  (sorted-map
    sync-core/prefix-key sync-core/config-defaults
    ms-core/prefix-key ms-core/config-defaults
    repl/prefix-key repl/config-defaults
    leihs-core/prefix-key leihs-core/config-defaults
    zapi-core/prefix-key zapi-core/config-defaults))

(def config* (atom default-config))

(defn shutdown []
  (repl/stop))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options :in-order true)
        pass-on-args (->> [options (rest arguments)]
                          flatten (into []))]
    (reset! main-options* options)
    (try
      (swap! config*
             (partial deep-merge-limited 1)
             (or (some-> options :config-file
                         slurp yaml/parse-string
                         (->> (into {})))
                 {}))
      (cond
        (:help
          options) (println
                     (main-usage summary {:args args :options options})
                     "\n\n"
                     "config: " @config*)
        (write-config-file-key
          options) (spit (write-config-file-key options)
                         (yaml/generate-string
                           @config*
                           :dumper-options {:flow-style :block}))
        :else (do (logging/debug 'CONFIG @config*)
                  (repl/init @config*)
                  (service-logging/init options)
                  (.addShutdownHook (Runtime/getRuntime) (Thread. #(shutdown)))
                  (sync-core/start @config*)))
      (catch Throwable th
        (reset! exception* th)
        (logging/error th)
        (when-not (:dev options)
          (System/exit -1))))
    (when-not (:dev options)
      (System/exit 0))))

;(-main "-d" "-c" "tmp/local-functional-test-config.secret.yml" "-h")
;(-main "-d" "--write-config-file" "config.yml")
;(logging/merge-config! {:min-level :debug})

;(-main "-h")
;(-main "-d" "-c" "local-functional-test-config.secret.yml" "run")

