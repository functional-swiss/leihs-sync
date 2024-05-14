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
    {:address (->> [:address1 :address2]
                   (map #(some-> zapi-person
                                 (get-in [:personal_contact %])
                                 presence))
                   (filter identity)
                   (clojure.string/join ", "))
     :badge_id (get-zapi-field zapi-person [:account :badge_number])
     :city (get-zapi-field zapi-person [:personal_contact :city])
     :country (when country-code (get de-iso-codes country-code))
     :email (get-zapi-field zapi-person [:account :email])
     :extended_info nil
     :firstname (get-zapi-field zapi-person [:basic :first_name])
     :zapi_img_url (get-zapi-field zapi-person [:photos_badge :photos 0 :resource_link :links :self])
     :img_digest (get-zapi-field zapi-person [:photos_badge :photos 0 :content_hash_sha1])
     :lastname (get-zapi-field zapi-person [:basic :last_name])
     :login (get-zapi-field zapi-person [:account :user_name])
     :org_id (str evento-id)
     :phone (or (get-zapi-field zapi-person [:business_contact :phone_business])
                (get-zapi-field zapi-person [:personal_contact :phone_business])
                (get-zapi-field zapi-person [:personal_contact :phone_mobile])
                (get-zapi-field zapi-person [:personal_contact :phone_private])
                (some-> zapi-person
                        (get-in [:personal_contact :phone_organizational])
                        first presence))
     :url (get-zapi-field zapi-person [:basic :url])
     :zip (->> [country-code
                (get-zapi-field zapi-person [:personal_contact :zip])]
               (map presence)
               (filter identity)
               (clojure.string/join "-"))}))

