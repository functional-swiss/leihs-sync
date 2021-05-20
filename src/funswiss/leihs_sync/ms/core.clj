(ns funswiss.leihs-sync.ms.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [clj-http.client :as http-client]
    [clj-yaml.core :as yaml]
    [clojure.set :as set]
    [clojure.string :as string]
    ;[clojure.tools.logging :as logging]
    [clojure.walk :refer [keywordize-keys stringify-keys]]
    [funswiss.leihs-sync.leihs.core :as leihs]
    [funswiss.leihs-sync.ms.auth :as ms-auth]
    [funswiss.leihs-sync.utils.cli-options :as cli-opts]
    [funswiss.leihs-sync.utils.core :refer [keyword presence str get! get-in!]]
    [logbug.catcher]
    [ring.util.codec :refer [url-encode]]
    [taoensso.timbre :as logging :refer [debug info spy]]
    ))

;;; options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def prefix-key ms-auth/prefix-key)
(def base-groups-key :base-groups)
(def base-groups-keys [prefix-key base-groups-key])


(def user-attribute-mapping-key :user-attribute-mapping)
(def user-attribute-mapping-keys [prefix-key user-attribute-mapping-key])
(def user-attribute-mapping-default {:mail :email
                                     :surname :lastname
                                     :id :org_id
                                     :givenName :firstname})

(def group-attribute-mapping-key :group-attribute-mapping)
(def group-attribute-mapping-keys [prefix-key group-attribute-mapping-key])
(def group-attribute-mapping-default {:id :org_id
                                      :description :description
                                      :displayName :name})

(def config-defaults
  (sorted-map
    base-groups-key []
    ms-auth/client-id-key nil
    ms-auth/client-secret-key nil
    ms-auth/tennant-id-key nil
    user-attribute-mapping-key user-attribute-mapping-default
    group-attribute-mapping-key group-attribute-mapping-default))


(defonce users* (atom nil))

;;; helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn select-query [sxs]
  (str "$select=" (->> sxs (map str)
                       set (set/union #{"id"})
                       (string/join ","))))

(defn seq->org-id-map [xs]
  (zipmap (map :org_id xs)
          xs))

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

(defn map-user-data-values [data]
  (as-> data data
    (if (contains? data :businessPhones)
      (update-in data [:businessPhones] first)
      data)
    (if (contains? data :otherMails)
      (update-in data [:otherMails] first)
      data)))

(defn map-user-data [config data]
  (as-> data data
    (map-user-data-values data)
    (assoc data "id" (:id data))
    (set/rename-keys data (get-in! config user-attribute-mapping-keys))
    (keywordize-keys data)
    (dissoc data
            (keyword "@odata.context")
            (keyword "@odata.type"))))

(defn map-users-data [config users]
  (->> users
       (map (partial map-user-data config))
       seq->org-id-map))

(defn all-users [config]
  (let [query (some->> [(-> config
                            (get-in! user-attribute-mapping-keys)
                            keys select-query)
                        "$filter=accountEnabled%20eq%20true"]
                       (string/join "&"))]
    (-> {:url (str "/users/?" query)}
        (seq-request config)
        (->> (map-users-data config)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn map-group-data [data config]
  (as-> data data
    (assoc data "id" (:id data))
    (set/rename-keys data (get-in! config group-attribute-mapping-keys))
    (keywordize-keys data)
    (dissoc data (keyword "@odata.context"))
    (merge {} data)))

(defn group-select [config]
  (-> config (get-in! group-attribute-mapping-keys) keys select-query))

(defn all-groups [config]
  (let
    [query (some->> [(-> config (get-in! group-attribute-mapping-keys)
                         keys select-query)]
                    (string/join "&"))
     url (str "/groups/?" query)]
    (-> {:url url}
        (seq-request config)
        (->> (map #(map-group-data % config))
             (map #(do [(:org_id %) %]))
             (into {})))))

(defn group [id config]
  (-> {:url (str "/groups/" id "?"
                 (some->> [(group-select config)]
                          (string/join "&")))}
      (base-request config) :body
      (map-group-data config)))

(defn group-user-filter [x]
  (= (x (keyword "@odata.type" ))
     "#microsoft.graph.user"))

(defn group-members [id config]
  (let [query (some->> [(-> config (get-in! user-attribute-mapping-keys)
                            vals select-query)]
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
  (let [query (->> [(-> config (get-in! user-attribute-mapping-keys)
                        keys select-query)]
                   (string/join "&"))]
    (-> {:url (str "/groups/" id "/transitiveMembers?" query)}
        (seq-request config)
        (->> (into [])
             (filter user-filter)
             (map-users-data config)))))

(defn group-groups [id config]
  "return the group members of the group with the given id, not recursive"
  (-> {:url (str "/groups/" id "/members/microsoft.graph.group"
                 "?" (some->> [(group-select config)]
                              (string/join "&")))}
      (seq-request config)
      (->> (map #(map-group-data % config))
           seq->org-id-map)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn photo-etag [org-id config]
  (let [id (get-in! @users* [org-id :id])]
    (some-> {:url (str "/users/" id "/photo")
             :unexceptional-status #(or (<= 200 % 299)
                                        (= % 401)
                                        (= % 404))}
            (base-request config)
            ((fn [resp]
               (case (:status resp)
                 401 (logging/warn (str "401 photo-etag for " org-id))
                 resp)))
            :body (get (keyword "@odata.mediaEtag"))
            yaml/parse-string presence)))

(defn photo [org-id config]
  (logging/info "TODO transform org-id into real id")
  (-> {:url (str "/users/" org-id "/photo/$value")
       :accept "*"
       :as :byte-array}
      (base-request config)
      :body))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn users [config]
  (logging/info "START ms/users")
  (let [res (if-let [base-groups (-> config (get-in! base-groups-keys) seq)]
              (loop [users {}
                     groups-ids base-groups]
                (if-let [group-id (first groups-ids)]
                  (recur (merge users (group-users group-id config))
                         (rest groups-ids))
                  users))
              (all-users config))]
    (reset! users* res)
    (logging/info "DONE ms/users #" (count @users*))
    res ))

(defn groups [config]
  (logging/info "START ns/groups")
  (let [res (if-let [groups-ids (-> config (get-in! base-groups-keys) seq)]
              (loop [groups (->> groups-ids
                                 (map #(group % config))
                                 (map (fn [g] [(:org_id g) g]))
                                 (into {}))
                     front groups
                     level 0]
                (let [next-front (reduce-kv
                                   (fn [m _ group]
                                     (merge m (group-groups (:id group) config)))
                                   {} front)
                      new-front (apply dissoc next-front (keys groups))]
                  (if (empty? new-front)
                    groups
                    (recur (merge groups new-front)
                           new-front
                           (inc level)))))
              (all-groups config))]
    (logging/info "DONE ns/groups #" (count res))
    res))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;timbre/*config*
;(logging/merge-config! {:level :debug})

