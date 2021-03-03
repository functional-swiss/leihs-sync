(ns funswiss.leihs-ms-connect.sync.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.tools.logging :as logging]
    [funswiss.leihs-ms-connect.leihs.core :as leihs]
    [funswiss.leihs-ms-connect.ms.core :as ms]
    [funswiss.leihs-ms-connect.sync.photo :as photo]
    [funswiss.leihs-ms-connect.utils.core :refer [keyword presence str get!]]
    [clojure.pprint :refer [pprint]]
    [clojure.set :as set]
    [logbug.catcher]
    [taoensso.timbre :as timbre :refer [debug info]]))

(defonce ms-users* (atom nil))
(defonce ms-groups* (atom nil))
(defonce leihs-users* (atom nil))
(defonce leihs-groups* (atom nil))
(defonce config* (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-users []
  (info "START add-users")
  (doseq [id (set/difference (-> @ms-users* keys set)
                             (-> @leihs-users* keys set))]
    (info "ADDING USER " id)
    (let [user (leihs/create-user
                 @config* (get @ms-users* id))]
      (swap! leihs-users* assoc id user)))
  (info "DONE add-users"))

(defn update-users []
  (info "START update-users")
  (doseq [[id ms-user] @ms-users*]
    (let [ks (-> ms-user (dissoc :id) keys)
          leihs-user (get! @leihs-users* id)
          to-be-updated-ks (filter
                             #(not= (% ms-user)
                                    (% leihs-user))
                             ks)]
      ;(doseq [k ks] (info k (not= (get k ms-user) (get k leihs-user))))
      ;(info {:id id :ms-user ms-user :leihs-user leihs-user :ks ks :to-be-updated-ks to-be-updated-ks})
      (when-let [data (some-> ms-user (select-keys to-be-updated-ks)
                              not-empty)]
        (info 'updating id data)
        (leihs/update-user @config* (:id leihs-user) data))))
  (info "DONE update-users"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn delete-groups []
  (info "START" 'delete-groups)
  (doseq [org-id (set/difference (-> @leihs-groups* keys set)
                                 (-> @ms-groups* keys set))]
    (info "DELETING GROUP" org-id)
    (let [group (leihs/delete-group
                  @config* (:id (get @leihs-groups* org-id)))]
      (swap! leihs-groups* dissoc org-id)))
  (info "DONE" 'delete-groups))

(defn add-groups []
  (info "START" 'add-groups)
  (doseq [org-id (set/difference (-> @ms-groups* keys set)
                                 (-> @leihs-groups* keys set))]
    (info "ADDING GROUP" org-id)
    (let [group (leihs/create-group
                  @config* (get @ms-groups* org-id))]
      (swap! leihs-groups* assoc org-id group)))
  (info "DONE" 'add-groups))

(defn update-groups []
  (info "START update-groups")
  (doseq [[org-id ms-group] @ms-groups*]
    (let [ks (-> ms-group (dissoc :id) keys)
          leihs-group (get! @leihs-groups* org-id)
          to-be-updated-ks (filter
                             #(not= (% ms-group)
                                    (% leihs-group))
                             ks)]
      ;(doseq [k ks] (info k (not= (get k ms-group) (get k leihs-group))))
      ;(info {:id id :ms-group ms-group :leihs-group leihs-group :ks ks :to-be-updated-ks to-be-updated-ks})
      (when-let [data (some-> ms-group
                              (select-keys to-be-updated-ks)
                              not-empty)]
        (info 'updating org-id data)
        (leihs/update-group @config* (:id leihs-group) data))))
  (info "DONE update-groups"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-groups-users []
  (info "START update-groups-users")
  (doseq [[org-id ms-group] @ms-groups*]
    (let [ms-group-id (-> @ms-groups* (get! org-id) :id)
          leihs-group-id (-> @leihs-groups* (get! org-id) :id)]
      ;(info "UPDATE-GROUPS-USERS " org-id)
      (let [target-ids (-> ms-group-id
                           (ms/group-users @config*)
                           keys set
                           (set/intersection (-> @leihs-users* keys set))
                           (->> (map #(get @leihs-users* %))
                                (map :id))
                           set)
            source-ids (-> leihs-group-id
                           (leihs/group-users @config*)
                           (->> (map :id)
                                set))]
        (when (not= target-ids source-ids)
          ;(info 'target-ids target-ids 'source-ids source-ids)
          (info "UPDATE-GROUPS-USERS " org-id)
          (leihs/update-group-users
            leihs-group-id @config* target-ids)))))
  (info "DONE update-groups-users"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-image [id]
  (when-let [etag (ms/photo-etag id @config*)]
    (info etag)
    (when (not= etag (get-in @leihs-users* [id :img_digest]))
      (info "getting image")
      (let [leihs-id (get-in @leihs-users* [id :id])
            img-bin (ms/photo id @config*)
            data {:img_digest etag
                  :img256_url (-> img-bin
                                  (photo/scale :size 256)
                                  photo/data-url)
                  :img32_url (-> img-bin
                                 (photo/scale :size 64)
                                 photo/data-url)}]
        (leihs/update-user @config* leihs-id data)
        (swap! leihs-users* assoc-in [id :img_digest] etag)))))

(defn update-images []
  (info "START update-images")
  (doseq [id (-> @ms-users* keys set)]
    (update-image id))
  (info "DONE update-images"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start [config]
  (reset! config* config)
  (logbug.catcher/snatch
    {}
    (let [org (get! config leihs/leihs-organization-key)]
      (reset! ms-users* (ms/users config))
      ;(reset! ms-groups* (ms/groups config))
      ;(reset! leihs-users* (leihs/users config))
      ;(reset! leihs-groups* (leihs/groups config))
      ;(delete-users)
      ;(add-users)
      ;(update-users)
      ;(delete-groups)
      ;(add-groups)
      ;(update-groups)
      ;(update-groups-users)
      ;(update-images)
      )))

;(info @config*)
;(info @ms-users*)
;(start @config*)
;(add-users @config* @ms-users* @leihs-users*)
