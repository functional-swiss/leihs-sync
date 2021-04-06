(ns funswiss.aad-leihs-sync.run
  (:refer-clojure :exclude [str keyword encode decode])
  (:require
    [clj-yaml.core :as yaml]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :as logging]
    [clojure.walk :refer [keywordize-keys stringify-keys]]
    [environ.core :refer [env]]
    [funswiss.aad-leihs-sync.leihs.core :as leihs-core]
    [funswiss.aad-leihs-sync.logging :as service-logging]
    [funswiss.aad-leihs-sync.ms.auth :as ms-auth]
    [funswiss.aad-leihs-sync.ms.core :as ms-core]
    [funswiss.aad-leihs-sync.sync.core :as sync-core]
    [funswiss.aad-leihs-sync.utils.cli-options :as cli-opts :refer [long-opt-for-key]]
    [funswiss.aad-leihs-sync.utils.core :refer [keyword str presence deep-merge]]
    [funswiss.aad-leihs-sync.utils.obscurity :as obscurity]
    [funswiss.aad-leihs-sync.utils.repl :as repl]
    [taoensso.timbre :as timbre :refer [debug info]]))


(def user-attribute-mapping-key :user-attribute-mapping)
(def user-attribute-mapping-default {:mail :email
                                     :surname :lastname
                                     :id :org_id
                                     :givenName :firstname})


(def group-attribute-mapping-key :group-attribute-mapping)
(def group-attribute-mapping-default {:id :org_id
                                      :description :description
                                      :displayName :name})



(defn options-specs []
  (concat
    [["-h" "--help"]
     [nil (cli-opts/long-opt-for-key user-attribute-mapping-key)
      "User-Attribute-Mapping MS2Leihs"
      :default (or (some-> user-attribute-mapping-key
                           cli-opts/default)
                   user-attribute-mapping-default)
      :parse-fn yaml/parse-string]
     [nil (cli-opts/long-opt-for-key group-attribute-mapping-key)
      "Group-Attribute-Mapping MS2Leihs"
      :default (or (some-> group-attribute-mapping-key
                           cli-opts/default)
                   group-attribute-mapping-default)
      :parse-fn yaml/parse-string]
     [nil "--config-write FILE"
      "Write the current configuration to a file and exit."]]
    (sync-core/options-specs)
    (ms-auth/options-specs)
    (ms-core/options-specs)
    (leihs-core/option-specs)
    (service-logging/option-specs)
    repl/cli-options))

(defn main-usage [options-summary & more]
  (->> ["add-leihs-sync run"
        ""
        "usage: add-leihs-sync [<opts>] run [<run-opts>] [<args>]"
        ""
        "Arguments to options can also be given through environment variables or java system properties."
        "Boolean arguments are parsed as YAML i.e. yes, no, true or false strings are interpreted. "
        ""
        "Run options:"
        options-summary
        ""
        ""
        (when more
          ["-------------------------------------------------------------------"
           (with-out-str (pprint more))
           "-------------------------------------------------------------------"])]
       flatten (clojure.string/join \newline)))

(defonce config* (atom nil))

(defn shutdown []
  (repl/stop))


(defn config-write []
  (spit (:config-write @config*)
        (-> @config*
            (dissoc :config-write)
            (update-in [leihs-core/leihs-token-key] #(some-> % obscurity/decrypt))
            (update-in [ms-auth/client-secret-key] #(some-> % obscurity/decrypt))
            (->> (filter (fn [[k v]] (-> v nil? not)))
                 (into (sorted-map)))
            (yaml/generate-string))))

(defn -main [& args]
  (info 'run/-main 'args args)
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args (options-specs) :in-order true)
        pass-on-args (->> [options (rest arguments)]
                          flatten (into []))]
    (reset! config*
            (deep-merge (sorted-map)
                        options
                        (when-let [path (:config-file options)]
                          (-> path slurp yaml/parse-string
                              (->> (into {}))))
                        (->> options
                             (filter (fn [[k v]] (-> v nil? not)))
                             (into {}))))
    (cond
      (:help options) (println (main-usage summary {:args args :options options}))
      (:config-write options) (config-write)
      :else (do (logging/info "running with options:" (str @config* ))
                (service-logging/init @config*)
                (.addShutdownHook (Runtime/getRuntime) (Thread. #(shutdown)))
                (repl/init @config*)
                (sync-core/start @config*)))))

;;; development ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hot-reload []
  ;(service-logging/init @config*)
  ;(init-http)
  )

; reload/restart stuff when requiring this file in dev mode
;(when @config* (hot-reload))
