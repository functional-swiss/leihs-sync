(ns zhdk.zapi.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [clj-http.client :as http-client]
    [funswiss.leihs-sync.utils.core :refer [deep-merge keyword presence str get! get-in!]]
    [logbug.catcher :as catcher]
    [logbug.debug :as debug :refer [I>]]
    [logbug.thrown :as thrown]
    [taoensso.timbre :as logging]
    [zhdk.zapi.user-mapping :as user-mapping])
  (:import
    [java.util Base64]))

(def prefix-key :zapi)
(def token-keys [prefix-key :token])
(def page-limit-keys [prefix-key :page-limit])

(def config-defaults
  (sorted-map
    (last token-keys) "TODO"
    (last page-limit-keys) 100))

(defn seq->map [k xs]
  (zipmap (map k xs) xs))

;;; person/people ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def person-fieldsets
  (->> ["account"
        "basic"
        "business_contact"
        "personal_contact"
        "photo"
        "photos_badge"
        "user_group"]
       (clojure.string/join "," )))

(defn- get-people-page-data [page config]
  (let [token (get-in! config token-keys)
        page-limit (get-in! config page-limit-keys)
        query-params {:offset (* page page-limit)
                      :limit page-limit
                      :only_zhdk false ; TODO communicate !
                      :fieldsets person-fieldsets
                      ;:last_name "schank"
                      ;:last_name "albrecht"
                      ;:last_name "kmit"
                      }]
    (-> (http-client/get
          "https://zapi.zhdk.ch/v1/person/"
          {:query-params query-params
           :accept :json
           :as :json
           :basic-auth [token ""]})
        :body :data)))

(defn- get-people [config]
  (loop [data [] page 0]
    (if-let [more-data (seq (get-people-page-data page config))]
      (recur (concat data more-data) (inc page))
      data)))

(defonce people* (atom nil))
(first @people*)

(defonce users* (atom nil))

(defn users [config]
  (logging/info "START zapi/users")
  (->> config
       (get-people)
       (seq->map :id)
       (reset! people*)
       vals
       (map user-mapping/zapi-person->leihs-attributes)
       (seq->map :org_id)
       (reset! users*))
  (logging/info "DONE zapi/users #" (count @users*))
  @users*)


;#### photo ###################################################################

(defn photo [org-id config]
  (let [token (get-in! config token-keys)
        url (-> @users* (get! org-id) :zapi_img_url)]
    (try (-> (http-client/get
               url
               {:accept :json
                :as :json
                :basic-auth [token ""]})
             :body
             :file_content_base64
             (.getBytes "UTF-8")
             (#(.decode (Base64/getDecoder) %)))
         (catch Exception e
           (throw (ex-info
                    "ZAPI get-photo error"
                    {:url url
                     :org-id org-id} e))))))

(defn photo-digest [org-id]
  (-> @users* (get! org-id) :img_digest))

;#### groups ##################################################################

(defn get-user-groups-page-data [page config]
  (let [token (get-in! config token-keys)
        page-limit (get-in! config page-limit-keys)
        query-params {:offset (* page page-limit)
                      :limit page-limit}]
    (-> (http-client/get
          "https://zapi.zhdk.ch/v1/user-group/"
          {:query-params query-params
           :accept :json
           :as :json
           :basic-auth [token ""]})
        :body :data)))

(defn get-user-groups [config]
  (loop [data [] page 0]
    (if-let [more-data (seq (get-user-groups-page-data page config))]
      (recur (concat data more-data) (inc page))
      data )))

(defn zapi-user-group->leihs-attributes [user-group]
  {:org_id (-> user-group (get! :id) str)
   :name (:name user-group)})

(defonce user-groups* (atom nil))

(defonce groups* (atom nil))

(defn groups [config]
  (logging/info "START zapi/groups")
  (->> config
       get-user-groups
       (reset! user-groups*)
       (map zapi-user-group->leihs-attributes)
       (seq->map :org_id)
       (reset! groups*))
  (logging/info "DONE zapi/groups #" (count @groups*))
  @groups*)

;#### group users #############################################################


(defn person-group-ids [person]
  (->> person
       :user_group
       :id
       (map :id)))

(def group-users* (atom {}))

(defn _set-group-users! []
  (doseq [[_ person] @people*]
    (logging/debug person)
    (doseq [group-id (person-group-ids person)]
      (logging/debug group-id)
      (swap! group-users* update-in [(str group-id)]
             (fn [members person-id]
               (conj (or members #{})
                     (str person-id)))
             (:id person)))))

(def set-group-users! (memoize _set-group-users!))

(defn group-users [org-id config]
  (set-group-users!)
  (get @group-users* org-id #{}))


;#### debug ###################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns 'cider-ci.utils.shutdown)
;(debug/debug-ns *ns*)
