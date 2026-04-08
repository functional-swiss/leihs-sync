(ns zhdk.zapi.user-mapping
  (:refer-clojure :exclude [str keyword])
  (:require
   [cheshire.core :as cheshire]
   [funswiss.leihs-sync.utils.core :refer [deep-merge keyword presence str get! get-in!]]
   [logbug.catcher :as catcher]
   [logbug.debug :as debug :refer [I>]]
   [logbug.thrown :as thrown]
   [taoensso.timbre.tools.logging]))

(def de-iso-codes
  (-> "iso-codes-de.json"
      clojure.java.io/resource
      slurp
      cheshire/parse-string
      (get "countries")))

(defn get-zapi-field [zapi-person ks]
  (some-> zapi-person (get-in ks) presence))

(defn zapi-person->leihs-attributes [zapi-person]
  (let [evento-id (:id zapi-person)
        country-code (get-zapi-field zapi-person [:personal_contact :country_code])]
    {:address nil
     :badge_id (get-zapi-field zapi-person [:account :badge_number])
     :city nil
     :country nil
     :email (get-zapi-field zapi-person [:account :email])
     ; check there is also [:business_contact :email]
     :secondary_email (get-zapi-field zapi-person [:personal_contact :email_private])
     :extended_info nil
     :firstname (get-zapi-field zapi-person [:basic :first_name])
     :zapi_img_url (get-zapi-field zapi-person [:photos_badge :photos 0 :resource_link :links :self])
     :img_digest (get-zapi-field zapi-person [:photos_badge :photos 0 :content_hash_sha1])
     :lastname (get-zapi-field zapi-person [:basic :last_name])
     :login (get-zapi-field zapi-person [:account :user_name])
     :org_id (str evento-id)
     :phone nil
     :url (get-zapi-field zapi-person [:basic :url])
     :zip nil}))

