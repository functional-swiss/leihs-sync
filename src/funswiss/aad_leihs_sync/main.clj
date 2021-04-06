(ns funswiss.aad-leihs-sync.main
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli]
    [funswiss.aad-leihs-sync.utils.cli-options :as cli-opts]
    [taoensso.timbre.tools.logging]
    ;[funswiss.aad-leihs-sync.logging :as logging]
    [funswiss.aad-leihs-sync.run :as run]
    [taoensso.timbre :as logging :refer [debug info spy]]
    )
  (:gen-class))

(def config-file-key :config-file)

(def cli-options
  (concat
    [["-h" "--help"]
     ["-d" "--dev" "DEV mode, does not exit"]
     ["-c" (cli-opts/long-opt-for-key config-file-key)
      "YAML configuration, values will be passed as defaults to SCOPE"
      :default nil]]))


(defn main-usage [options-summary & more]
  (->> ["add-leihs-sync"
        ""
        "usage: add-leihs-sync [<opts>] SCOPE [<scope-opts>] [<args>]"
        ""
        "available scopes: run"
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

(defn -main [& args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options :in-order true)
        pass-on-args (->> [options (rest arguments)]
                          flatten (into []))]
    (reset! main-options* options)
    (try
      (if (:help options)
        (println (main-usage summary {:args args :options options}))
        (do
          (when-let [path (config-file-key options)]
            (cli-opts/set-config-file-defaults! path))
          (case (-> arguments first keyword)
            :run (apply run/-main (rest arguments))
            (println (main-usage summary {:args args :options options})))))
      (catch Throwable th
        (reset! exception* th)
        (logging/error th)
        (when-not (:dev options)
          (System/exit -1))))
    (when-not (:dev options)
      (System/exit 0))))


;(-main "run" "-h")
;(-main "-d" "-c" "local-functional-test-config.secret.yml" "run")
;(-main "-d" "-c" "local-functional-test-config.secret.yml" "run")
;(logging/merge-config! {:level :debug})
