(ns funswiss.leihs-sync.sync.photo
  (:refer-clojure :exclude [str keyword])
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell :refer [sh]]
   [clojure.pprint :refer [pprint]]
   [clojure.set :as set]
   [clojure.tools.logging :as logging]
   [funswiss.leihs-sync.ms.core :as ms]
   [funswiss.leihs-sync.utils.core :refer [keyword presence str get!]]
   [logbug.catcher]
   [taoensso.timbre :as timbre :refer [debug info]])
  (:import
   [java.util Base64]))

(defn scale
  [bx & {:keys [size x y quality]
         :or {size 256
              x size
              y size
              quality 95}}]
  (let [cmd ["convert" "-"
             "-quality" (str quality)
             "-resize" (str x "x" y ">")
             "-background" "LightGray"
             "-gravity" "center"
             "-extent" (str x "x" y)
             "-strip"
             "jpg:-"
             :in bx :out-enc :bytes]
        {:keys [exit out err]} (apply sh cmd)]
    (when (not= 0 exit)
      (throw (ex-info (str "scale shellout error: " err)
                      {:cmd cmd :exit exit :err err})))
    out))

(def IMG-DATA-URL-PREFIX "data:image/jpeg;base64,")

(defn data-url [bx]
  (str IMG-DATA-URL-PREFIX (.encodeToString (Base64/getEncoder) bx)))

