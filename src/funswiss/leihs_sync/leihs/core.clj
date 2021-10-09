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
    [taoensso.timbre :as logging :refer [debug info spy]]
    ))

(def base-url-key :base-url)
(def token-key :token)
;(defn option-specs [] [{:key :leihs-base-url} ])

(def prefix-key :leihs)

(def config-defaults
  (sorted-map
    base-url-key "http://localhost:3200"
    token-key "TODO"))

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
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])
        fields (disj user-keys-read :img32_url :img256_url)
        query {:per-page 1000
               :fields (cheshire/generate-string fields)
               :account_enabled ""
               :organization (get-in! config [:core :organization])}]
    (loop
      [page 1
       users []]
      (if-let [more-users (seq (-> (http-client/get
                                     (str base-url "/admin/users/")
                                     {:query-params
                                      (assoc query :page page)
                                      :accept :json
                                      :as :json
                                      :basic-auth [token ""]})
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
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])]
    (-> (str base-url "/admin/users/")
        (http-client/post
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [token ""]
           :body (-> user
                     keywordize-keys
                     (select-keys user-keys-writeable)
                     cheshire/generate-string)})
        :body)))

(defn update-user [config id data]
  (logging/debug 'update-user config id data)
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])]
    (-> (str base-url "/admin/users/" id)
        (http-client/patch
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [token ""]
           :body (-> data
                     keywordize-keys
                     (select-keys user-keys-writeable)
                     cheshire/generate-string)})
        :body)))

(defn delete-user [config id]
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])]
    (-> (str base-url "/admin/users/" id)
        (http-client/delete
          {:accept :json
           :basic-auth [token ""]}))))

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
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])
        fields group-keys-read
        query {:per-page 1000
               :fields (cheshire/generate-string fields)
               :account_enabled ""
               :organization (get-in! config [:core :organization])}]
    (loop
      [page 1
       groups []]
      (if-let [more-groups (seq (-> (http-client/get
                                     (str base-url "/admin/groups/")
                                     {:query-params
                                      (assoc query :page page)
                                      :accept :json
                                      :as :json
                                      :basic-auth [token ""]})
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
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])]
    (-> (str base-url "/admin/groups/")
        (http-client/post
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [token ""]
           :body (-> group
                     keywordize-keys
                     (select-keys group-keys-writeable)
                     cheshire/generate-string )})
        :body)))

(defn update-group [config id data]
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])]
    (-> (str base-url "/admin/groups/" id)
        (http-client/patch
          {:accept :json
           :as :json
           :content-type :json
           :basic-auth [token ""]
           :body (-> data
                     keywordize-keys
                     (select-keys group-keys-writeable)
                     cheshire/generate-string )})
        :body)))

(defn delete-group [config id]
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])]
    (-> (str base-url "/admin/groups/" id)
        (http-client/delete
          {:accept :json
           :basic-auth [token ""]}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn group-users [id config]
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])
        query {:per-page 1000 }]
    (loop
      [page 1
       users []]
      (if-let [more-users (seq (-> (http-client/get
                                     (str base-url "/admin/groups/" id "/users/")
                                     {:query-params
                                      (assoc query :page page)
                                      :accept :json
                                      :as :json
                                      :basic-auth [token ""]})
                                   :body :users))]
        (recur (inc page)
               (concat users more-users))
        users))))

(defn update-group-users [id config ids]
  (let [base-url (get-in! config [prefix-key base-url-key])
        token (get-in! config [prefix-key token-key])]
    (http-client/put
      (str base-url "/admin/groups/" id "/users/")
      {:accept :json
       :content-type :json
       :as :json
       :basic-auth [token ""]
       :body (cheshire/generate-string
               {:ids ids})})))
