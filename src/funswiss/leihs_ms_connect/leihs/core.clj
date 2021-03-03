(ns funswiss.leihs-ms-connect.leihs.core
  (:refer-clojure :exclude [str keyword])
  (:require
    [cheshire.core :as cheshire]
    [clj-http.client :as http-client]
    [clj-yaml.core :as yaml]
    [clojure.set :as set]
    [clojure.string :as string]
    [clojure.tools.logging :as logging]
    [clojure.walk :refer [keywordize-keys stringify-keys]]
    [funswiss.leihs-ms-connect.ms.auth :as ms-auth]
    [funswiss.leihs-ms-connect.utils.cli-options :as cli-opts]
    [funswiss.leihs-ms-connect.utils.core :refer [keyword presence str get!]]
    [funswiss.leihs-ms-connect.utils.obscurity :as obscurity]
    [logbug.catcher]
    [taoensso.timbre :as timbre :refer [debug info spy]]
    ))

(def leihs-base-url-key :leihs-base-url)
(def leihs-token-key :leihs-token)
(def leihs-organization-key :leihs-organization)
;(defn option-specs [] [{:key :leihs-base-url} ])

(defn option-specs []
  (->>
    [{:key leihs-base-url-key}
     {:key :leihs-user-defaults}
     {:key :leihs-group-defaults}
     {:key leihs-organization-key}
     {:key leihs-token-key
      :default (some-> (cli-opts/default leihs-token-key) obscurity/encrypt)
      :parse-fn #(some-> % presence obscurity/encrypt)}]
    cli-opts/normalize-option-specs))

;(cli-opts/compile-option-specs (option-specs))

(defn users-fields [config]
  (set/union
    #{:id
      :organization
      :img_digest
      :org_id}
    (->> (get! config :user-attribute-mapping)
         vals
         (map keyword)
         set)
    (->> (get config :leihs-user-defaults {})
         keys set)))

(def write-allowed-user-keys
  [:account_enabled
   :address
   :admin_protected
   :badge_id
   :city
   :country
   :email
   :extended_info
   :firstname
   :img256_url
   :img32_url
   :img_digest
   :is_admin
   :is_system_admin
   :language_locale
   :lastname
   :login
   :org_id
   :organization
   :password_sign_in_enabled
   :phone
   :secondary_email
   :system_admin_protected
   :url
   :zip])

(defn request-users [config]
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)
        fields (users-fields config)
        query {:per-page 1000
               :fields (cheshire/generate-string fields)
               :account_enabled ""
               :organization (get! config leihs-organization-key)}]
    (loop
      [page 1
       users []]
      (if-let [more-users (seq (-> (http-client/get
                                     (str base-url "/admin/users/")
                                     {:query-params
                                      (assoc query :page page)
                                      :accept :json
                                      :as :json
                                      :basic-auth [(obscurity/decrypt enc-token) ""]})
                                   :body :users))]
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
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)]
    (-> (str base-url "/admin/users/")
        (http-client/post
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [(obscurity/decrypt enc-token) ""]
           :body (-> user
                     keywordize-keys
                     (select-keys write-allowed-user-keys)
                     cheshire/generate-string)})
        :body)))

(defn update-user [config id data]
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)]
    (-> (str base-url "/admin/users/" id)
        (http-client/patch
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [(obscurity/decrypt enc-token) ""]
           :body (-> data
                     keywordize-keys
                     (select-keys write-allowed-user-keys)
                     cheshire/generate-string)})
        :body)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn groups-fields [config]
  (set/union
    #{:id
      :organization
      :org_id}
    (->> (get! config :group-attribute-mapping)
         vals
         (map keyword)
         set)
    (->> (get config :leihs-group-defaults {})
         keys set)))

(def write-allowed-group-keys
  [:admin_protected
   :description
   :name
   :org_id
   :organization
   :system_admin_protected])

(defn request-groups [config]
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)
        fields (groups-fields config)
        query {:per-page 1000
               :fields (cheshire/generate-string fields)
               :account_enabled ""
               :organization (get! config leihs-organization-key)}]
    (loop
      [page 1
       groups []]
      (if-let [more-groups (seq (-> (http-client/get
                                     (str base-url "/admin/groups/")
                                     {:query-params
                                      (assoc query :page page)
                                      :accept :json
                                      :as :json
                                      :basic-auth [(obscurity/decrypt enc-token) ""]})
                                   :body :groups))]
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
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)]
    (-> (str base-url "/admin/groups/")
        (http-client/post
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [(obscurity/decrypt enc-token) ""]
           :body (-> group
                     keywordize-keys
                     (select-keys write-allowed-group-keys)
                     cheshire/generate-string )})
        :body)))

(defn update-group [config id data]
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)]
    (-> (str base-url "/admin/groups/" id)
        (http-client/patch
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [(obscurity/decrypt enc-token) ""]
           :body (-> data
                     keywordize-keys
                     (select-keys write-allowed-group-keys)
                     cheshire/generate-string )})
        :body)))

(defn delete-group [config id]
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)]
    (-> (str base-url "/admin/groups/" id)
        (http-client/delete
          {:accept :json
           :basic-auth [(obscurity/decrypt enc-token) ""]}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn group-users [id config]
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)
        query {:per-page 1000
               ; TODO
               ;:fields (cheshire/generate-string [:id, :org_id, :email, :login])
               }]
    (loop
      [page 1
       users []]
      (if-let [more-users (seq (-> (http-client/get
                                     (str base-url "/admin/groups/" id "/users/")
                                     {:query-params
                                      (assoc query :page page)
                                      :accept :json
                                      :as :json
                                      :basic-auth [(obscurity/decrypt enc-token) ""]})
                                   :body :users))]
        (recur (inc page)
               (concat users more-users))
        users))))

(defn update-group-users [id config ids]
  (let [base-url (get! config leihs-base-url-key)
        enc-token (get! config leihs-token-key)]
    (http-client/put
      (str base-url "/admin/groups/" id "/users/")
      {:accept :json
       :content-type :json
       :as :json
       :basic-auth [(obscurity/decrypt enc-token) ""]
       :body (cheshire/generate-string
               {:ids ids})})))
