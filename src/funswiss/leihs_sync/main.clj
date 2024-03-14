(ns funswiss.leihs-sync.main
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli]
    [clojure.set :as set]
    [clj-yaml.core :as yaml]
    [clojure.string :as string]
    [funswiss.leihs-sync.leihs.core :as leihs-core]
    [funswiss.leihs-sync.ms.core :as ms-core]
    [funswiss.leihs-sync.sync.core :as sync-core]
    [funswiss.leihs-sync.utils.cli-options :as cli-opts]
    [funswiss.leihs-sync.utils.core :refer [deep-merge-limited keyword presence str get! get-in!]]
    [funswiss.leihs-sync.utils.logging :as service-logging]
    [funswiss.leihs-sync.utils.repl :as repl]
    [funswiss.leihs-sync.zabbix-sender :as zabbix-sender]
    [logbug.thrown :as thrown]
    [taoensso.timbre :as logging :refer [debug info spy]]
    [taoensso.timbre.tools.logging]
    [zhdk.prtg :as prtg]
    [zhdk.zapi.core :as zapi-core])
  (:import
    [java.time LocalDateTime Instant])
  (:gen-class))


(thrown/reset-ns-filter-regex #"^(funswiss|zhdk)\..*")

(def config-file-key :config-file)
(def write-config-file-key :write-config-file)

(def REPO "https://github.com/functional-swiss/leihs-sync")

(defn version-info []
  (or (some-> "version.yml" clojure.java.io/resource
              slurp yaml/parse-string)
      {:commit-id "DEV"
       :commit-timestamp (str (LocalDateTime/now))
       :build-timestamp (str (LocalDateTime/now))}))

(defn version-str []
  (let [version (version-info)
        commit-id (-> version :commit-id (#(subs % 0 (min 7 (count %)))))]
    (str "Build: " (:build-timestamp version) " "
         "URL: " REPO "/commit/" commit-id )))

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
      :update-fn conj]
     ["-v" "--version"]]))

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
    zapi-core/prefix-key zapi-core/config-defaults
    zabbix-sender/prefix-key zabbix-sender/config-defaults
    ))

(def config* (atom default-config))

(defn shutdown []
  (repl/stop))

(defn main [args]
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
        (:version
          options) (println (version-str))
        (write-config-file-key
          options) (spit (write-config-file-key options)
                         (yaml/generate-string
                           @config*
                           :dumper-options {:flow-style :block}))
        :else (do (logging/debug 'CONFIG @config*)
                  (repl/init @config*)
                  (service-logging/init options)
                  (.addShutdownHook (Runtime/getRuntime) (Thread. #(shutdown)))

                  (logging/info "leihs-sync" (version-str))
                  (sync-core/start @config*)
                  (when-let [prtg-url (get @config* :prtg-url)]
                    (prtg/send-success prtg-url @sync-core/state*))
                  (zabbix-sender/send-success @config* @sync-core/state*)
                  ))
      (catch Throwable th
        (reset! exception* th)
        (logging/error (thrown/stringify th))
        (when-let [prtg-url (get @config* :prtg-url)]
          (prtg/send-error prtg-url th))
        (when-not (:dev options)
          (System/exit -1))))
    (when-not (:dev options)
      (System/exit 0))))


(defonce args* (atom nil))
(when @args* (main @args*))

(defn -main [& args]
  (reset! args* args)
  (main args))


;(-main "-d" "-c" "tmp/local-functional-test-config.secret.yml" "-h")
;(-main "-d" "--write-config-file" "config.yml")
;(logging/merge-config! {:min-level :debug})

;(-main "-h")
;(-main "-d" "-c" "local-functional-test-config.secret.yml" "run")

