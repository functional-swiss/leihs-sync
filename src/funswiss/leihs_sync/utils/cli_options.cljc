(ns funswiss.leihs-sync.utils.cli-options
  (:refer-clojure :exclude [str keyword encode decode])
  (:require
   [camel-snake-kebab.core :refer [->snake_case]]
   [clj-yaml.core :as yaml]
   [clojure.string :refer [upper-case]]
   [clojure.tools.cli :as cli]
   [environ.core :refer [env]]
   [funswiss.leihs-sync.utils.core :refer [keyword str presence deep-merge]]
   [taoensso.timbre :as timbre :refer [debug info]]))

(defonce config-file-defaults* (atom {}))

(defn set-config-file-defaults! [path]
  (reset! config-file-defaults*
          (-> path slurp yaml/parse-string
              (->> (into {})))))

(defn extract-options-keys [cli-options]
  (->> cli-options
       (map (fn [option]
              (or (seq (drop-while #(not= :id %) option))
                  (throw (ex-info
                          "option requires :id to extract-options-keys"
                          {:option option})))))
       (map second)))

(defn long-opt-for-key [k]
  (str "--" k " " (-> k str ->snake_case upper-case)))

(defn default [k]
  (or (k @config-file-defaults*)
      (k env)))

;;; scratch below ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def compile-option-specs #'cli/compile-option-specs)

(defn normalize-option [option]
  (let [k (:key option)]
    (->>
     (as-> option opt
       (dissoc opt :key)
       (assoc opt :id k)
       (if-not (contains? opt :default)
         (assoc opt :default (default k))
         opt)
       (assoc opt :long-opt (str "--" k))
       (assoc opt :required (-> k str ->snake_case upper-case)))
     (mapcat identity)
     vec)))

;(info (normalize-option {:key :leihs-base-url}))

(defn normalize-option-specs [option-specs]
  (->> option-specs
       (map (fn [option]
              (if-not (map? option)
                option
                (normalize-option option))))))
