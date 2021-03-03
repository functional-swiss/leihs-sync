(ns funswiss.leihs-ms-connect.main
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli]
    [funswiss.leihs-ms-connect.utils.cli-options :as cli-opts]
    [taoensso.timbre.tools.logging]
    [funswiss.leihs-ms-connect.logging :as logging]
    [funswiss.leihs-ms-connect.run :as run])
  (:gen-class))

(def config-file-key :config-file)

(def cli-options
  (concat
    [["-h" "--help"]
     ["-c" (cli-opts/long-opt-for-key config-file-key)
      "YAML configuration, values will be passed as defaults to SCOPE"
      :default nil]]))


(defn main-usage [options-summary & more]
  (->> ["leihs-ms-connect"
        ""
        "usage: leihs-ms-connect [<opts>] SCOPE [<scope-opts>] [<args>]"
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

(defn -main [& args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options :in-order true)
        pass-on-args (->> [options (rest arguments)]
                          flatten (into []))]
    (reset! main-options* options)

    (if (:help options) (println (main-usage summary {:args args :options options}))
      (do
        (when-let [path (config-file-key options)] (cli-opts/set-config-file-defaults! path))
        (case (-> arguments first keyword)
          :run (apply run/-main (rest arguments))
          (println (main-usage summary {:args args :options options})))))))


;(-main "-c" "config.yml" "-h")
;(-main "-c" "config.yml" "run" "-h")
;(-main "-c" "functional-test-config.secret.yml" "run" "--repl" "false")
