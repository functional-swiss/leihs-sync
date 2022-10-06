(ns funswiss.leihs-sync.sync.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.set :as set]
    [funswiss.leihs-sync.leihs.core :as leihs]
    [funswiss.leihs-sync.ms.core :as ms]
    [funswiss.leihs-sync.sync.photo :as photo]
    [funswiss.leihs-sync.utils.cli-options :as cli-opts :refer [long-opt-for-key]]
    [funswiss.leihs-sync.utils.core :refer [keyword presence str get! get-in!]]
    [logbug.catcher]
    [taoensso.timbre :as logging :refer [error warn info debug spy]]
    [zhdk.zapi.core :as zapi])
  (:import
    [clojure.lang ExceptionInfo]))


(def prefix-key :core)

(def organization-key :organization)

(def source-key :source)

(def user-photo-mode-key :user-photo-mode)
(def user-photo-mode-keys [prefix-key user-photo-mode-key])
(def user-photo-mode-default "lazy")

(def user-create-defaults-key :user-create-defaults)
(def user-update-defaults-key :user-update-defaults)

(def user-disable-properties-key :user-disable-properties)
(def user-disable-properties {:account_enabled false
                                    :address nil
                                    :badge_id nil
                                    :city nil
                                    :country nil
                                    :email nil
                                    :extended_info nil
                                    :firstname nil
                                    :img_digest nil
                                    :lastname nil
                                    :login nil
                                    :phone nil
                                    :secondary_email nil
                                    :url nil
                                    :zip nil })

(def group-filter-blacklist-regexes-key :group-filter-blacklist-regexes)
(def group-filter-blacklist-regexes-default [])

(def group-create-defaults-key :group-create-defaults)
(def group-update-defaults-key :group-update-defaults)

(def group-filter-whitelist-regex-key :group-filter-whitelist-regex)
(def group-filter-whitelist-regex-default ".*")

(def config-defaults
  (sorted-map
    group-filter-blacklist-regexes-key group-filter-blacklist-regexes-default
    group-filter-whitelist-regex-key group-filter-whitelist-regex-default
    group-create-defaults-key {}
    group-update-defaults-key {}
    user-create-defaults-key {}
    user-disable-properties-key user-disable-properties
    user-update-defaults-key {}
    organization-key "TODO"
    source-key "ms"
    user-photo-mode-key user-photo-mode-default ))

(def initial-state
  {:errors []
   :groups-created-count 0
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

(defn source-kw [] (-> @config* (get-in! [prefix-key source-key]) keyword))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-user [org-id]
  (debug "ADDING USER " org-id)
  (let [user (leihs/create-user
               @config* (merge
                          (get-in! @config* [prefix-key user-create-defaults-key])
                          (get! @nominal-users* org-id)))]
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
  (doseq [[org-id plain-source-user] @nominal-users*]
    (let [user-update-defaults (get-in! @config*
                                        [prefix-key user-update-defaults-key])
          source-user (merge plain-source-user user-update-defaults)
          ks (-> source-user
                 (dissoc :id)
                 (select-keys leihs/user-keys-writeable)
                 (dissoc :img_digest :img32_url :img256_url)
                 keys)
          leihs-user (-> @leihs-users*
                         (get! org-id))
          to-be-updated-ks (filter
                             #(not= (% source-user)
                                    (% leihs-user))
                             ks)]
      ;(doseq [k ks] (info k (not= (get k source-user) (get k leihs-user))))
      ;(info {:org_id org-id :source-user source-user :leihs-user leihs-user :ks ks :to-be-updated-ks to-be-updated-ks})
      (when-let [data (some-> source-user (select-keys to-be-updated-ks)
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
  (let [properties (get-in! @config* [prefix-key user-disable-properties-key])
        ks (keys properties)
        org-id (:org_id leihs-user)
        to-be-updated-ks (filter #(not= (% properties)
                                        (% leihs-user)) ks)]
    (when-let [data (some-> properties
                            (select-keys to-be-updated-ks)
                            disable-image-props
                            not-empty)]
      (debug 'DISABLING org-id data)
      (leihs/update-user @config* (:id leihs-user) data)
      (swap! leihs-users* update-in [org-id] #(merge % data))
      (swap! state* update-in [:users-disabled-count] inc))))

(defn delete-user [leihs-user]
  (leihs/delete-user @config* (:id leihs-user))
  (swap! leihs-users* dissoc (:org_id leihs-user))
  (swap! state* update-in [:users-deleted-count] inc))

(defn delete-or-disable-users []
  (info "START delete-or-disable-users")
  (doseq [org-id (set/difference (-> @leihs-users* keys set )
                                 (-> @nominal-users* keys set))]
    (let [leihs-user (get! @leihs-users* org-id)]
      ; do not delete account if it was used to sign-in (can't because of audits and more
      ; always disable if has been disabled before (see catch below)
      (if (or (:last_sign_in_at leihs-user)
              (-> :account_enabled leihs-user not))
        (disable-user leihs-user)
        ; there are a few corner cases where :last_sign_in_at is not sufficient
        ; to determe if an account can be deleted
        (try (delete-user leihs-user)
             (catch ExceptionInfo e
               (if (contains? #{409 422}
                              (some-> e ex-data :status))
                 (disable-user leihs-user)
                 (do (warn "Deleting user-account "
                           leihs-user " failed with"
                           (str (.getMessage e)))
                     (throw e))))))))
  (info "DONE delete-or-disable-users deleted: " (-> @state* :users-deleted-count)
        ", disabled: " (-> @state* :users-disabled-count)))

(defn users-total-enabled-disabled-count-stat []
  (->> @leihs-users*
       (map second)
       (reduce (fn [agg user]
                 (let [inc-kw (if (:account_enabled user)
                                :users-total-enabled-count
                                :users-total-disabled-count)]
                   (update agg inc-kw inc)))
               {:users-total-enabled-count 0
                :users-total-disabled-count 0})))

(defn stat-users []
  (swap! state* merge (users-total-enabled-disabled-count-stat)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def _all-users-group
  (memoize (fn [config]
             (let [org (get-in! config [prefix-key organization-key])
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

(defn create-groups []
  (info "START" 'create-groups)
  (doseq [org-id (set/difference (-> @nominal-groups* keys set)
                                 (-> @leihs-groups* keys set))]
    (debug "create group" org-id)
    (let [group (leihs/create-group
                  @config* (merge
                             (get-in! @config* [prefix-key group-create-defaults-key])
                             (get @nominal-groups* org-id)))]
      (swap! leihs-groups* assoc org-id group))
    (swap! state* update-in [:groups-created-count] inc))
  (info "DONE" 'create-groups))

(defn update-groups []
  (info "START update-groups")
  (doseq [[org-id plain-nominal-group] @nominal-groups*]
    (let [group-update-defaults (get-in! @config*
                                         [prefix-key group-create-defaults-key])
          nominal-group (merge group-update-defaults plain-nominal-group)
          ks (-> nominal-group (dissoc :id) keys)
          leihs-group (get! @leihs-groups* org-id)
          to-be-updated-ks (filter
                             #(not= (% nominal-group)
                                    (% leihs-group))
                             ks)]
      ;(doseq [k ks] (info k (not= (get k nominal-group) (get k leihs-group))))
      ;(info {:id id :nominal-group nominal-group :leihs-group leihs-group :ks ks :to-be-updated-ks to-be-updated-ks})
      (when-let [data (some-> nominal-group
                              (select-keys to-be-updated-ks)
                              not-empty)]
        (info 'updating org-id data)
        (leihs/update-group @config* (:id leihs-group) data)
        (swap! state* update-in [:groups-updated-count] inc))))
  (info "DONE update-groups #" (:groups-updated-count @state*)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn source-group-users [org-id]
  (if (= org-id (:org_id (all-users-group)))
    (-> @nominal-users* keys set)
    (case (source-kw)
      :ms (-> org-id (ms/group-users @config*) keys set)
      :zapi (-> org-id (zapi/group-users @config*)))))

(defn update-groups-users []
  (info "START update-groups-users")
  (doseq [[org-id _] @nominal-groups*]
    (let [leihs-group-id (-> @leihs-groups* (get! org-id) :id)]
      (debug "UPDATE-GROUPS-USERS " org-id)
      (let [target-ids (-> org-id
                           source-group-users
                           (set/intersection (-> @leihs-users* keys set))
                           (->> (map #(get @leihs-users* %))
                                (map :id))
                           set)
            source-ids (-> leihs-group-id
                           (leihs/group-users @config*)
                           (->> (map :id)
                                set))]
        (debug {:source-ids source-ids :target-ids target-ids})
        (when (not= target-ids source-ids)
          (debug "UPDATE-GROUPS-USERS " org-id)
          (leihs/update-group-users
            leihs-group-id @config* target-ids)
          (swap! state* update-in [:groups-users-updated-count] inc)
          ))))
  (info "DONE update-groups-users #" (:groups-users-updated-count @state*)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn img-url [img-bin size limit]
  (loop [quality 95]
    (let [img (-> img-bin
                  (photo/scale :size size :quality quality)
                  photo/data-url)
          size (count img)]
      (logging/debug {:size size :limit limit :quality quality})
      (if (> limit size)
        img
        (if (<= quality 0)
          (throw (ex-info "unscalable image " {}))
          (recur (- quality 5)))))))

(defn get-and-build-image-data [digest org-id]
  (let [img-bin (case (source-kw)
                  :ms (ms/photo org-id @config*)
                  :zapi (zapi/photo org-id @config*))]
    {:img_digest digest
     :img256_url (img-url img-bin 256 100000)
     :img32_url (img-url img-bin 64 10000)}))

(defn check-and-update-image [{org-id :org_id :as leihs-user}]
  "returns the id if the image has been updated and nil otherwise"
  (debug 'check-and-update-image org-id)
  (swap! state* update-in [:users-photos-checked] inc)
  (let [target-digest (case (source-kw)
                        :ms (ms/photo-etag org-id @config*)
                        :zapi (zapi/photo-digest org-id))
        current-digest (get-in @leihs-users* [org-id :img_digest])]
    (debug {:target-digest target-digest :current-digest current-digest})
    (when (not= target-digest current-digest)
      (debug (str "updating image for " org-id " target-digest: " target-digest))
      (let [data (if target-digest
                   (get-and-build-image-data target-digest org-id)
                   {:img_digest nil :img256_url nil :img32_url nil})
            leihs-id (get-in! @leihs-users* [org-id :id])]
        ;(logging/debug 'img-data data)
        (leihs/update-user @config* leihs-id data)
        (swap! leihs-users* assoc-in [org-id :img_digest] target-digest)
        (swap! state* update-in [:users-photos-updated] inc)
        leihs-user))))

(defn update-images []
  (info "START update-images")
  (case (get-in! @config* user-photo-mode-keys)
    "eager" (->> @leihs-users* vals
                 (filter :account_enabled)
                 (map check-and-update-image)
                 doall)
    "lazy" (->> @leihs-users* vals
                (filter :account_enabled)
                (filter :last_sign_in_at)
                (map check-and-update-image)
                doall)
    "none" nil)
  (info "DONE update-images #" (select-keys @state* [:users-photos-checked
                                                     :users-photos-updated])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn name-blacklist-filter [blacklist-filters entity]
  (empty? (->> blacklist-filters
               (map #(re-matches % (-> entity :name presence (or ""))))
               (filter identity))))

(defn nominal-groups [config]
  (let [org (get-in! config [prefix-key organization-key])
        blacklist-filters (-> @config*
                              (get-in! [prefix-key group-filter-blacklist-regexes-key])
                              (->> (map re-pattern)))
        whitelist-filter (-> @config*
                             (get-in! [prefix-key group-filter-whitelist-regex-key])
                             re-pattern)
        groups (case (source-kw)
                 :ms (ms/groups config)
                 :zapi (zapi/groups config))]
    (as-> groups groups
      (assoc groups (:org_id (all-users-group)) (all-users-group))
      (filter (fn [[_ group]]
                (name-blacklist-filter blacklist-filters group))
              groups)
      (filter (fn [[_ group]]
                (re-matches whitelist-filter
                            (-> group :name presence (or ""))))
              groups)
      (map (fn [[org-id group]]
             [org-id (assoc group :organization org)])
           groups)
      (into {} groups))))

(defn nominal-users [config]
  (let [org (get-in! config [prefix-key organization-key])]
    (->> (case (source-kw)
           :ms (ms/users config)
           :zapi (zapi/users config))
         (map (fn [[org-id user]]
                [org-id (assoc user :organization org)]))
         (into {}))))

(defn start [config]
  (reset! state* initial-state)
  (reset! config* config)
  (reset! nominal-users* (nominal-users config))
  (reset! nominal-groups* (nominal-groups config))
  (reset! leihs-users* (leihs/users config))
  (reset! leihs-groups* (leihs/groups config))
  (delete-or-disable-users)
  (create-users)
  (update-users)
  (delete-groups)
  (create-groups)
  (update-groups)
  (update-groups-users)
  (update-images)
  (stat-users)
  (info "STATE" @state*))


;(first @nominal-groups*)
;(first @nominal-users*)
;(ms/group "f2c49a49-fc49-42db-b145-ab42f07fdb8d" @config*)
;(ms/all-groups @config*)
;(reset! nominal-users* (ms/all-users @config*))
;(ms/users @config*)
;(-> @leihs-users* )
;(info @config*)
;(info @nominal-users*)
;(start @config*)
;(create-users @config* @nominal-users* @leihs-users*)
;(logging/merge-config! {:min-level :debug})
