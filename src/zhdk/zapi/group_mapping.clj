(ns zhdk.zapi.group-mapping
  (:refer-clojure :exclude [str keyword])
  (:require
    [funswiss.leihs-sync.utils.core :refer [deep-merge keyword presence str get! get-in!]]
    [cheshire.core :as cheshire]
    [logbug.catcher :as catcher]
    [logbug.debug :as debug :refer [I>]]
    [logbug.thrown :as thrown]
    [taoensso.timbre.tools.logging]
    ))



(defn zapi-user-group->leihs-attributes [user-group]
  {:org_id (get! user-group :id)
   :name (:name user-group)
   })

