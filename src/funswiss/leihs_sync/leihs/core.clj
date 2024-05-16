(ns funswiss.leihs-sync.leihs.core
  (:refer-clojure :exclude [str keyword])
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as http-client]
   [clj-yaml.core :as yaml]
   [clojure.set :as set]
   [clojure.string :as string]
    ;[clojure.tools.logging :as logging]
   [clojure.walk :refer [keywordize-keys stringify-keys]]
   [funswiss.leihs-sync.ms.auth :as ms-auth]
   [funswiss.leihs-sync.utils.cli-options :as cli-opts]
   [funswiss.leihs-sync.utils.core :refer [keyword presence str get! get-in!]]
   [logbug.catcher]
   [taoensso.timbre :as logging :refer [debug info spy error]]))

(def base-url-key :base-url)
(def token-key :token)
;(defn option-specs [] [{:key :leihs-base-url} ])

(def prefix-key :leihs)

(def config-defaults
  (sorted-map
   base-url-key "http://localhost:3200"
   token-key "TODO"))

(defn request-base [config]
  (as-> {:accept :json
         :as :json} req
    (if-let [token (get-in! config [prefix-key token-key])]
      (assoc-in req [:headers :authorization] (str "token " token))
      req)
    (if-let [base-url (get-in! config [prefix-key base-url-key])]
      (assoc req :url base-url)
      req)))

(def user-keys-read
  #{:account_disabled_at
    :account_enabled
    :address
    :admin_protected
    :badge_id
    :city
    :country
    :email
    :extended_info
    :firstname
    :id
    :img256_url
    :img32_url
    :img_digest
    :is_admin
    :is_system_admin
    :last_sign_in_at
    :lastname
    :login
    :org_id
    :organization
    :password_sign_in_enabled
    :phone
    :secondary_email
    :system_admin_protected
    :url
    :zip})

(def user-keys-writeable
  (-> user-keys-read
      (disj :account_disabled_at
            :id
            :last_sign_in_at)
      (conj :img256_url :img32_url)))

(defn request-users [config]
  (let [fields (disj user-keys-read :img32_url :img256_url)
        query {:per-page 1000
               :fields (cheshire/generate-string fields)
               :account_enabled ""
               :organization (get-in! config [:core :organization])}]
    (loop
     [page 1
      users []]
     (if-let [more-users (-> config request-base
                             (update-in [:url] #(str % "/admin/users/"))
                             (assoc :method :get
                                    :query-params (assoc query :page page))
                             http-client/request
                             :body :users
                             seq)]
        (recur (inc page)
               (concat users more-users))
        users))))

(defn users [config]
  (info "GET leihs-users")
  (let [users (->> (request-users config)
                   (map #(do [(:org_id %) %]))
                   (into {}))]
    (info "GOT " (count users) " leihs users")
    users))

(defn create-user [config user]
  (let [body (-> user
                 keywordize-keys
                 (select-keys user-keys-writeable)
                 cheshire/generate-string)]
    (try (-> config
             request-base
             (update-in [:url] #(str % "/admin/users/"))
             (assoc :method :post
                    :content-type :json
                    :body body)
             http-client/request
             :body)
         (catch Exception ex
           (error "create-user faild" {:body body})
           (throw ex)))))

(defn update-user [config id data]
  (logging/debug 'update-user config id data)
  (try (-> config
           request-base
           (update-in [:url] #(str % "/admin/users/" id))
           (assoc :method :patch
                  :content-type :json
                  :body (-> data
                            keywordize-keys
                            (select-keys user-keys-writeable)
                            cheshire/generate-string))
           http-client/request
           :body)
       (catch Throwable e
         (error "update-user failed " id data)
         (throw e))))

(defn delete-user [config id]
  (http-client/delete
   (-> config
       request-base
       (update-in [:url] #(str % "/admin/users/" id))
       (assoc
        :method :delete
        :throw-entire-message? true)
       http-client/request)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def group-keys-read
  #{:id
    :name
    :organization
    :org_id
    :description
    :admin_protected
    :system_admin_protected})

(def group-keys-writeable
  (disj group-keys-read :id))

(defn request-groups [config]
  (let [fields group-keys-read
        query {:per-page 1000
               :fields (cheshire/generate-string fields)
               :account_enabled ""
               :organization (get-in! config [:core :organization])}]
    (loop
     [page 1
      groups []]
     (if-let [more-groups (-> config
                              request-base
                              (update-in [:url] #(str % "/admin/groups/"))
                              (assoc :method :get
                                     :query-params
                                     (assoc query :page page))
                              http-client/request
                              :body :groups
                              seq)]
        (recur (inc page)
               (concat groups more-groups))
        groups))))

(defn groups [config]
  (info "GET leihs-groups")
  (let [groups (->> (request-groups config)
                    (map #(do [(:org_id %) %]))
                    (into {}))]
    (info "GOT " (count groups) " leihs groups")
    groups))

(defn create-group [config group]
  (try (-> config
           request-base
           (update-in [:url] #(str % "/admin/groups/"))
           (assoc :method :post
                  :content-type :json
                  :body (-> group
                            keywordize-keys
                            (select-keys group-keys-writeable)
                            cheshire/generate-string))
           http-client/request
           :body)
       (catch Exception ex
         (error "create-group failed" {:group group})
         (throw ex))))

(defn update-group [config id data]
  (try (-> config
           request-base
           (update-in [:url] #(str % "/admin/groups/" id))
           (assoc :method :patch
                  :content-type :json
                  :body (-> data
                            keywordize-keys
                            (select-keys group-keys-writeable)
                            cheshire/generate-string))
           http-client/request
           :body)
       (catch Throwable e
         (error "update-group failed" {:id id :data data})
         (throw e))))

(defn delete-group [config id]
  (try (-> config
           request-base
           (update-in [:url] #(str % "/admin/groups/" id))
           (assoc :method :delete)
           http-client/request)
       (catch Throwable e
         (error "delete-group failed" {:id id})
         (throw e))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-users [id config]
  (loop
    [page 1
     users []]
    (if-let [more-users (-> config request-base
                            (update-in [:url] #(str % "/admin/groups/" id "/users/"))
                            (assoc :method :get
                                   :query-params {:per-page 1000 :page page})
                            http-client/request
                            :body :users
                            seq)]
      (recur (inc page)
             (concat users more-users))
      users)))

(defn update-group-users [id config ids]
  (-> config request-base
      (update-in [:url] #(str % "/admin/groups/" id "/users/"))
      (assoc :method :put
             :content-type :json
             :body (cheshire/generate-string {:ids ids}))
      http-client/request))
