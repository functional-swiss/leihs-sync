(ns funswiss.aad-leihs-sync.sync.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [funswiss.aad-leihs-sync.leihs.core :as leihs]
    [funswiss.aad-leihs-sync.ms.core :as ms]
    [funswiss.aad-leihs-sync.sync.photo :as photo]
    [funswiss.aad-leihs-sync.utils.cli-options :as cli-opts :refer [long-opt-for-key]]
    [funswiss.aad-leihs-sync.utils.core :refer [keyword presence str get!]]
    [clojure.pprint :refer [pprint]]
    [clojure.set :as set]
    [logbug.catcher]
    [taoensso.timbre :as logging :refer [debug info]]))


(def user-photo-mode-key :user-photo-mode)

(defn options-specs []
  (->>
    [{:key user-photo-mode-key
      :default (or (some-> user-photo-mode-key cli-opts/default presence)
                   "lazy")}]
    cli-opts/normalize-option-specs))


(def initial-state
  {:errors []
   :groups-added-count 0
   :groups-deleted-count 0
   :groups-updated-count 0
   :groups-users-updated-count 0
   :users-created-count 0
   :users-deleted-count 0
   :users-disabled-count 0
   :users-photos-checked 0
   :users-photos-updated 0
   :users-updated-count 0 })

(defonce state* (atom initial-state))

(defonce nominal-users* (atom nil))
(defonce nominal-groups* (atom nil))
(defonce leihs-users* (atom nil))
(defonce leihs-groups* (atom nil))
(defonce config* (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-user [org-id]
  (debug "ADDING USER " org-id)
  (let [user (leihs/create-user
               @config* (get @nominal-users* org-id))]
    (swap! leihs-users* assoc org-id user))
  (swap! state* update-in [:users-created-count] inc)
  org-id)

(defn create-users []
  (info "START create-users")
  (->> (set/difference
         (-> @nominal-users* keys set)
         (-> @leihs-users* keys set))
       (map create-user)
       doall)
  (info "DONE create-users #" (-> @state* :users-created-count)))

(defn update-users []
  (info "START update-users")
  (doseq [[org-id ms-user] @nominal-users*]
    (let [ks (-> ms-user (dissoc :id) keys)
          leihs-user (get! @leihs-users* org-id)
          to-be-updated-ks (filter
                             #(not= (% ms-user)
                                    (% leihs-user))
                             ks)]
      ;(doseq [k ks] (info k (not= (get k ms-user) (get k leihs-user))))
      ;(info {:org_id org-id :ms-user ms-user :leihs-user leihs-user :ks ks :to-be-updated-ks to-be-updated-ks})
      (when-let [data (some-> ms-user (select-keys to-be-updated-ks)
                              not-empty)]
        (debug 'updating org-id data)
        (leihs/update-user @config* (:id leihs-user) data)
        (swap! leihs-users* update-in [org-id] #(merge % data))
        (swap! state* update-in [:users-updated-count] inc))))
  (info "DONE update-users #" (-> @state* :users-updated-count)))

(defn disable-image-props [props]
  (if-not (contains? props :img_digest)
    props
    (assoc props
           :img256_url nil
           :img32_url nil)))

(defn disable-user [leihs-user]
  (let [properties (get! @config* leihs/leihs-user-disable-properties-key)
        ks (keys properties)
        to-be-updated-ks (filter #(not= (% properties)
                                        (% leihs-user)) ks)]
    (when-let [data (some-> properties
                            (select-keys to-be-updated-ks)
                            disable-image-props
                            not-empty)]
      (debug 'DISABLING (:org_id leihs-user) data)
      (swap! state* update-in [:users-disabled-count] inc)
      (leihs/update-user @config* (:id leihs-user) data))))

(defn delete-user [leihs-user]
  (leihs/delete-user @config* (:id leihs-user))
  (swap! leihs-users* dissoc (:org_id leihs-user))
  (swap! state* update-in [:users-deleted-count] inc))

(defn delete-or-disable-users []
  (info "START delete-or-disable-users")
  (doseq [org-id (set/difference (-> @leihs-users* keys set)
                                 (-> @nominal-users* keys set))]
    (let [leihs-user (get! @leihs-users* org-id)]
      (if (:last_sign_in_at leihs-user)
        (disable-user leihs-user)
        (delete-user leihs-user))))
  (info "DONE delete-or-disable-users deleted: " (-> @state* :users-deleted-count)
        ", disabled: " (-> @state* :users-disabled-count)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def _all-users-group
  (memoize (fn [config]
             (let [org (get! config leihs/leihs-organization-key)
                   org-id (str "all-users-synced_" org)]
               {:org_id org-id
                :id org-id
                :organization org
                :name (str "All users synced from " org)
                :description (str "This is automatically generated group. "
                                  "It includes all synced users from " org ".")}))))

(defn all-users-group []
  (_all-users-group @config*))

(defn delete-groups []
  (info "START" 'delete-groups)
  (doseq [org-id (set/difference (-> @leihs-groups* keys set)
                                 (-> @nominal-groups* keys set))]
    (info "DELETING GROUP" org-id)
    (let [group (leihs/delete-group
                  @config* (:id (get @leihs-groups* org-id)))]
      (swap! leihs-groups* dissoc org-id))
    (swap! state* update-in [:groups-deleted-count] inc))
  (info "DONE delete-groups #" (-> @state* :groups-deleted-count)))

(defn add-groups []
  (info "START" 'add-groups)
  (doseq [org-id (set/difference (-> @nominal-groups* keys set)
                                 (-> @leihs-groups* keys set))]
    (debug "ADDING GROUP" org-id)
    (let [group (leihs/create-group
                  @config* (get @nominal-groups* org-id))]
      (swap! leihs-groups* assoc org-id group))
    (swap! state* update-in [:groups-added-count] inc))
  (info "DONE" 'add-groups))

(defn update-groups []
  (info "START update-groups")
  (doseq [[org-id ms-group] @nominal-groups*]
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
        (leihs/update-group @config* (:id leihs-group) data)
        (swap! state* update-in [:groups-updated-count] inc))))
  (info "DONE update-groups #" (:groups-updated-count @state*)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn source-group-users [source-group-id]
  (cond (= source-group-id
           (:id (all-users-group))) @nominal-users*
        :else (ms/group-users source-group-id @config*)))

(defn update-groups-users []
  (info "START update-groups-users")
  (doseq [[org-id ms-group] @nominal-groups*]
    (let [ms-group-id (-> @nominal-groups* (get! org-id) :id)
          leihs-group-id (-> @leihs-groups* (get! org-id) :id)]
      ;(info "UPDATE-GROUPS-USERS " org-id)
      (let [target-ids (-> ms-group-id
                           (source-group-users)
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
            leihs-group-id @config* target-ids)
          (swap! state* update-in [:groups-users-updated-count] inc)
          ))))
  (info "DONE update-groups-users #" (:groups-users-updated-count @state*)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn check-and-update-image [id]
  "returns the id if the image has been updated and nil otherwise"
  (debug 'check-and-update-image id)
  (swap! state* update-in [:users-photos-checked] inc)
  (when-let [etag (ms/photo-etag id @config*)]
    (when (not= etag (get-in @leihs-users* [id :img_digest]))
      (debug (str "updating image for " id " etag: " etag))
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
        (swap! leihs-users* assoc-in [id :img_digest] etag)
        (swap! state* update-in [:users-photos-updated] inc)
        id))))

(defn update-images []
  (info "START update-images")
  (case (get! @config* user-photo-mode-key)
    "eager" (->> @nominal-users* vals (map :id)
                 (map check-and-update-image)
                 doall)
    "lazy" (->> @leihs-users* vals
                (filter :account_enabled)
                (filter :last_sign_in_at)
                (map :org_id)
                (map check-and-update-image)
                doall)
    "none" nil)
  (info "DONE update-images #" (select-keys @state* [:users-photos-checked
                                                     :users-photos-updated])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nominal-groups [config]
  (->> (assoc (ms/groups config)
              (:org_id (all-users-group)) (all-users-group))
       (map (fn [[org-id group]]
              [org-id (merge {}
                             (get! config :leihs-group-defaults)
                             group)]))
       (into {})))

(defn nominal-users [config]
  (->>
    (ms/users config)
    (map (fn [[org-id user]]
           [org-id (merge {}
                          (get! config :leihs-user-defaults)
                          user)]))
    (into {})))

(defn start [config]
  (reset! state* initial-state)
  (reset! config* config)
  (reset! nominal-users* (nominal-users config) )
  (reset! nominal-groups* (nominal-groups config))
  (reset! leihs-users* (leihs/users config))
  (reset! leihs-groups* (leihs/groups config))
  (delete-or-disable-users)
  (create-users)
  (update-users)
  (delete-groups)
  (add-groups)
  (update-groups)
  (update-groups-users)
  (update-images)
  (info "STATE" @state*)
  )

;(ms/group "f2c49a49-fc49-42db-b145-ab42f07fdb8d" @config*)
;(ms/all-groups @config*)
;(ms/users @config*)
;(-> @leihs-users* )
;(info @config*)
;(info @nominal-users*)
;(start @config*)
;(create-users @config* @nominal-users* @leihs-users*)
;(logging/merge-config! {:level :debug})
