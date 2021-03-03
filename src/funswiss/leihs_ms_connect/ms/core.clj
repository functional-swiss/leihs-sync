(ns funswiss.leihs-ms-connect.ms.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [clj-http.client :as http-client]
    [clj-yaml.core :as yaml]
    [clojure.set :as set]
    [clojure.string :as string]
    [clojure.tools.logging :as logging]
    [clojure.walk :refer [keywordize-keys stringify-keys]]
    [funswiss.leihs-ms-connect.leihs.core :as leihs]
    [funswiss.leihs-ms-connect.ms.auth :as ms-auth]
    [funswiss.leihs-ms-connect.utils.cli-options :as cli-opts]
    [funswiss.leihs-ms-connect.utils.core :refer [keyword presence str get!]]
    [logbug.catcher]
    [ring.util.codec :refer [url-encode]]
    [taoensso.timbre :as timbre :refer [debug info spy]]
    ))

;;; options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def base-groups-key :base-groups)

(defn options-specs []
  (->>
    [{:key base-groups-key
      :desc "Seq of groups from which groups and users are taken transitivly. If nil all users and all groups in the direcory will be synced."
      :parse-fn yaml/parse-string}]
    cli-opts/normalize-option-specs
    ))


;;; helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn select-query [sxs]
  (str "$select=" (->> sxs (map str)
                       set (set/union #{"id"})
                       (string/join ","))))

;;; request ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def BASE-URL "https://graph.microsoft.com/v1.0")

(defn prefix-url [url]
  (if (string/starts-with? url "http")
    url
    (str BASE-URL url)))

(def request-defaults
  {:method :get
   :accept :json
   :as :json})

(defn base-request
  [params config & {:keys [modify]
                    :or {modify identity}}]
  (-> request-defaults
      (ms-auth/add-auth-header config)
      (merge params)
      (update-in [:url] prefix-url)
      modify
      http-client/request))

(defn map-request [params config]
  (-> params
      (base-request config)
      :body :value))

(defn seq-request [params config]
  (loop [url (:url params)
         value []]
    (let [res (base-request (assoc params :url url) config)
          value (concat value (-> res :body :value))]
      (if-let [next-url (get-in res [:body (keyword "@odata.nextLink")])]
        (recur next-url value)
        value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn map-users-data [config users]
  (->> users
       (map #(set/rename-keys % (:user-attribute-mapping config)))
       (map keywordize-keys)
       (map #(assoc % :organization (get! config leihs/leihs-organization-key)))
       (map #(merge {} (get config :leihs-user-defaults) %))
       (map #(do [(:org_id %) %]))
       (into {})))

(defn directory-users [config]
  (let [query (some->> [(->> config :user-attribute-mapping keys select-query)
                        "$filter=accountEnabled%20eq%20true"]
                       (string/join "&"))]
    (-> {:url (str "/users/?" query)}
        (seq-request config)
        (->> (map-users-data config)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn groups [config]
  (let
    [query (some->> [(->> config :group-attribute-mapping keys select-query)
                     (str "$filter=" (url-encode "groupTypes/any(c:c+eq+'Unified')"))]
                    (string/join "&"))
     url (str "/groups/?" query)]
    (logging/info 'url url)
    (-> {:url url}
        (seq-request config)
        (->>
          (map #(set/rename-keys % (:group-attribute-mapping config)))
          (map keywordize-keys)
          (map #(assoc % :organization (get! config leihs/leihs-organization-key)))
          (map #(merge {} (get config :leihs-group-defaults) %))
          (map #(do [(:org_id %) %]))
          (into {})))))

(defn group-user-filter [x]
  (= (x (keyword "@odata.type" ))
     "#microsoft.graph.user"))

(defn group-members [id config]
  (let [query (some->> [(->> config :user-attribute-mapping vals select-query)]
                       (string/join "&"))]
    (-> {:url (str "/groups/" id "/transitiveMembers"
                   (when query (str "?" query)))}
        (seq-request config)
        (->> (filter group-user-filter)
             (map :id)
             set))))

(defn user-filter [x]
  (= (x (keyword "@odata.type" )) "#microsoft.graph.user"))

(defn group-users [id config]
  (logbug.catcher/snatch
    {}
    (let [query (some->> [(->> config :user-attribute-mapping
                               keys select-query)]
                         (string/join "&"))]
      (-> {:url (str "/groups/" id "/transitiveMembers?" query)}
          (seq-request config)
          (->> (filter user-filter)
               (map-users-data config))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn photo-etag [id config]
  (some-> {:url (str "/users/" id "/photo")
           :unexceptional-status #(or (<= 200 % 299)
                                      (= % 404))}
          (base-request config)
          :body (get (keyword "@odata.mediaEtag"))
          yaml/parse-string presence))

(defn photo [id config]
  (-> {:url (str "/users/" id "/photo/$value")
       :accept "*"
       :as :byte-array}
      (base-request config)
      :body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn users [config]
  (if-let [base-groups (get config base-groups-key)]
    (loop [users {}
           groups-ids base-groups]
      (if-let [group-id (first groups-ids)]
        (recur (merge users (group-users group-id config))
               (rest groups-ids))
        users))
    (directory-users config)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;  timbre/*config*
(timbre/merge-config! {:level :info})

