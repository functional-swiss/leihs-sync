(ns funswiss.leihs-sync.ms.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [clj-http.client :as http-client]
    [clj-yaml.core :as yaml]
    [clojure.set :as set]
    [clojure.string :as string]
    [clojure.walk :refer [keywordize-keys stringify-keys]]
    [funswiss.leihs-sync.leihs.core :as leihs]
    [funswiss.leihs-sync.ms.auth :as ms-auth]
    [funswiss.leihs-sync.utils.cli-options :as cli-opts]
    [funswiss.leihs-sync.utils.core :refer [keyword presence str get! get-in!]]
    [logbug.catcher]
    [ring.util.codec :refer [url-encode]]
    [taoensso.timbre :as timbre :refer [error warn info debug spy]]
    ))

(def MAX_RETRIES 3)


;;; options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def prefix-key ms-auth/prefix-key)


(def base-groups-key :base-groups)
(def base-groups-keys [prefix-key base-groups-key])


;;; data ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce users-raw* (atom nil))
(defonce users-mapped* (atom nil))

(comment
  (->> @users-raw*
       (map (fn [[k vs]]
              (some-> vs
                  :onPremisesExtensionAttributes
                  :extensionAttribute8
                  str
                  (clojure.string/split #"@")
                  first
                  )))
       (filter identity)
       ))


(defonce groups-raw* (atom  nil))
(defonce groups-mapped* (atom  nil))

;;; user ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def user-request-properties-preset
  #{:city
    :businessPhones
    :country
    :givenName
    :id
    :mail
    :mobilePhone
    :otherMails
    :postalCode
    :streetAddress
    :surname })

(def user-request-additional-properties-key :user-request-additional-properties)
(def user-request-additional-properties-keys [prefix-key user-request-additional-properties-key])

(def user-attribute-mapping-preset
  {:address :streetAddress
   :city :city
   :country :country
   :email :mail
   :firstname :givenName
   :id :id
   :lastname :surname
   :org_id :id
   :phone #(some->> (concat [] (:businessPhones %) [(:mobilePhone %)]) (filter identity) first)
   :secondary_email #(some-> % :otherMails first)
   :zip :postalCode })

(def user-attributes-custom-mapping-key :user-attributes-custom-mapping)
(def user-attributes-custom-mapping-keys [prefix-key user-attributes-custom-mapping-key])


;;; group ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def group-request-properties-preset
  #{:description
    :displayName
    :id})

(def group-attribute-mapping-preset
  {:id :id
   :org_id :id
   :description :description
   :name :displayName})

(def group-attributes-custom-mapping-key :group-attributes-custom-mapping)
(def group-attributes-custom-mapping-keys [prefix-key group-attributes-custom-mapping-key])

(def group-request-additional-properties-key :group-request-additional-properties)
(def group-request-additional-properties-keys [prefix-key group-request-additional-properties-key])

(def config-defaults
  (sorted-map
    base-groups-key []
    ms-auth/client-id-key nil
    ms-auth/client-secret-key nil
    ms-auth/tenant-id-key nil
    ms-auth/http-client-defaults-key ms-auth/http-client-defaults
    user-attributes-custom-mapping-key {}
    user-request-additional-properties-key []
    group-attributes-custom-mapping-key {}
    group-request-additional-properties-key []))

;;; helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn select-query [sxs]
  (str "$select=" (->> sxs (map str)
                       set (set/union #{"id"})
                       (string/join ","))))

(defn assert-id [entity id-kw]
  (when-not (presence (get entity id-kw))
    (throw
      (ex-info (str "entity has no or empty "
                    id-kw " attribute ") {:entity entity}))))

(defn warn-id [entity id-kw]
  (when-not (presence (get entity id-kw))
    (warn (str "Entity has no " id-kw) entity)))

(defn seq->id-map [xs]
  (doseq [x xs] (assert-id x :id))
  (zipmap (map :id xs) xs))

(defn id-map->org-id-map [m]
  (let [xs (vals m)]
    (doseq [x xs] (assert-id x :org_id))
    (zipmap (map :org_id xs) xs)))

(defn seq->org-id-map [xs]
  (doseq [x xs] (warn-id x :org_id))
  (let [xs-clean (->> xs (filter #(get % :org_id)))]
    (zipmap (map :org_id xs-clean) xs-clean)))


;;; request ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def BASE-URL "https://graph.microsoft.com/v1.0")

(defn prefix-url [url]
  (if (string/starts-with? url "http")
    url
    (str BASE-URL url)))

(defn request-defaults [config]
  (merge
    {}
    (get-in! config ms-auth/http-client-defaults-keys)
    {:method :get
     :accept :json
     :as :json}))

(defn base-request
  [params config & {:keys [modify]
                    :or {modify identity}}]
  (loop [retry 0]
    (Thread/sleep (* retry retry 10 1000))
    (if-let [res (try  (-> (request-defaults config)
                           (ms-auth/add-auth-header config)
                           (merge params)
                           (update-in [:url] prefix-url)
                           modify
                           http-client/request)
                      (catch clojure.lang.ExceptionInfo ex
                        (warn "Cought request exception " ex)
                        (when (<= MAX_RETRIES retry)
                          (throw (ex-info "To many request retries "
                                          {:status (-> ex ex-data :status)
                                           :params params})))
                        (when (not= (-> ex ex-data :status) 504)
                          (throw ex))))]
      res
      (recur (inc retry)))))

(defn map-request [params config]
  (-> params
      (base-request config)
      :body :value))

(defn seq-request [config params]
  (loop [url (:url params)
         value []]
    (let [res (base-request (assoc params :url url) config)
          value (concat value (-> res :body :value))]
      (if-let [next-url (get-in res [:body (keyword "@odata.nextLink")])]
        (recur next-url value)
        value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn users-property-select-query-part [config]
  (->> user-request-additional-properties-keys
       (get-in! config)
       (concat user-request-properties-preset)
       select-query))

(defn users-query [config]
  (string/join
    "&"
    [(users-property-select-query-part config)]))

(defn map-entity-data [mapping data]
  (->> mapping
       (map (fn [[k v]]
              [k (cond
                   (keyword? v) (get data v)
                   (string? v) (get data (keyword v))
                   (fn? v) (v data)
                   (map? v) (if-let [fun (get v :fn)]
                              (apply (eval (read-string fun)) [data])
                              (throw (ex-info "fn not supplied" {}))))]))
       (into {})))

(defn map-user-data [config data]
  (map-entity-data
    (merge user-attribute-mapping-preset
           (get-in! config user-attributes-custom-mapping-keys))
    data))

(defn map-users-data [config users]
  (->> users
       (map (partial map-user-data config))
       seq->org-id-map))

(defn all-users [config]
  (->> {:url (str "/users/?" (users-query config))}
       (seq-request config)
       seq->id-map))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn map-group-data [config data]
  (map-entity-data
    (merge group-attribute-mapping-preset
           (get-in! config group-attributes-custom-mapping-keys))
    data))

(defn groups-property-select-query-part [config]
  (->> group-request-additional-properties-keys
       (get-in! config)
       (concat group-request-properties-preset)
       select-query))

(defn groups-query [config]
  (string/join
    "&"
    [(groups-property-select-query-part config)]))

(defn all-groups [config]
  (->> {:url (str "/groups/?" (groups-query config))}
       (seq-request config)
       seq->id-map))

(defn group [id config]
  (-> {:url (str "/groups/" id "?" (groups-query config))}
      (base-request config) :body
      ))

(defn group-user-filter [x]
  (= (x (keyword "@odata.type" ))
     "#microsoft.graph.user"))

(defn user-filter [x]
  (= (x (keyword "@odata.type" )) "#microsoft.graph.user"))

(defn group-users [id config]
  (debug 'group-users id)
  (->> {:url (str "/groups/" id "/transitiveMembers?" (users-query config))}
       (seq-request config)
       (filter user-filter)
       seq->id-map))

(defn group-groups [config {id :id}]
  "return the group members of the group with the given id, not recursive"
  (->> {:url (str "/groups/" id "/members/microsoft.graph.group"
                  "?"  (groups-query config))}
       (seq-request config)
       seq->id-map))

(defn groups-recursive [config base-groups]
  (loop [groups (->> base-groups
                     (map #(group % config))
                     seq->id-map)
         front groups
         level 0]
    (let [next-front (reduce-kv
                       (fn [m _ group]
                         (merge m (group-groups config group)))
                       {} front)
          new-front (apply dissoc next-front (keys groups))]
      (if (empty? new-front)
        groups
        (recur (merge groups new-front)
               new-front
               (inc level))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn photo-etag [org-id config]
  (let [id (get-in! @users-mapped* [org-id :id])]
    (some-> {:url (str "/users/" id "/photo")
             :unexceptional-status #(or (<= 200 % 299)
                                        (= % 401)
                                        (= % 404))}
            (base-request config)
            ((fn [resp]
               (case (:status resp)
                 401 (warn (str "401 photo-etag for " org-id))
                 resp)))
            :body (get (keyword "@odata.mediaEtag"))
            yaml/parse-string presence)))

(defn photo [org-id config]
  (let [id (get-in! @users-mapped* [org-id :id])]
    (-> {:url (str "/users/" org-id "/photo/$value")
         :accept "*"
         :as :byte-array}
        (base-request config)
        :body)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn groups-users [config groups-ids]
  (loop [users {}
         groups-ids groups-ids]
    (if-let [group-id (first groups-ids)]
      (recur (merge users (group-users group-id config))
             (rest groups-ids))
      users)))

(defn users [config]
  (info "START ms/users")
  (->>
    (if-let [base-groups-ids (-> config (get-in! base-groups-keys) seq)]
      (groups-users config base-groups-ids)
      (all-users config))
    (reset! users-raw*)
    vals
    (map (partial map-user-data config))
    (reset! users-mapped*)
    seq->org-id-map
    (reset! users-mapped*))
  (info "DONE ms/users #" (count @users-mapped*))
  @users-mapped*)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn groups [config]
  (info "START ns/groups")
  (->> (if-let [base-groups (-> config (get-in! base-groups-keys) seq)]
         (groups-recursive config base-groups)
         (all-groups config))
       (reset! groups-raw*)
       vals
       (map (partial map-group-data config))
       (reset! groups-mapped*)
       (seq->org-id-map)
       (reset! groups-mapped*))
  (info "DONE ns/groups #" (count @groups-mapped*))
  @groups-mapped*)



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;timbre/*config*
;(timbre/set-ns-min-level! :debug)


