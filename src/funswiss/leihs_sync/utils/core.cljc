(ns funswiss.leihs-sync.utils.core
  (:refer-clojure :exclude [str keyword])
  (:require
   [taoensso.timbre :as logging :refer [debug info spy]]))

(defn str
  "Like clojure.core/str but maps keywords to strings without preceding colon."
  ([] "")
  ([x]
   (if (keyword? x)
     (subs (clojure.core/str x) 1)
     (clojure.core/str x)))
  ([x & yx]
   (apply clojure.core/str (concat [(str x)] (apply str yx)))))

(defn keyword
  "Like clojure.core/keyword but coerces an unknown single argument x
  with (-> x cider-ci.utils.core/str cider-ci.utils.core/keyword).
  In contrast clojure.core/keyword will return nil for anything
  not being a String, Symbol or a Keyword already (including
  java.util.UUID, Integer)."
  ([name] (cond (keyword? name) name
                :else (clojure.core/keyword (str name))))
  ([ns name] (clojure.core/keyword ns name)))

(defn deep-merge [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))

(defn deep-merge-limited [max-level & vals]
  (logging/info 'max-level max-level 'vals vals)
  (if-not (every? map? vals)
    (last vals)
    (if (= max-level 0)
      (apply merge vals)
      (apply merge-with (partial deep-merge-limited (dec max-level)) vals))))

(comment
  (deep-merge-limited
   5
   {:a {:b1 {:c1 1
             :c2 2}
        :b2 "foo"}}
   {:a {:b1 {:c1 7}}}))

(defn presence [v]
  "Returns nil if v is a blank string or if v is an empty collection.
   Returns v otherwise."
  (cond
    (string? v) (if (clojure.string/blank? v) nil v)
    (coll? v) (if (empty? v) nil v)
    :else v))

(defn presence! [v]
  "Pipes v through presence returns the result of that iff it is not nil.
  Throws an IllegalStateException otherwise. "
  (or (-> v presence)
      (throw
       (new
        #?(:clj IllegalStateException
           :cljs js/Error)
        "The argument must neither be nil, nor an empty string nor an empty collection."))))

(defn get! [m k]
  (when-not (contains? m k)
    (throw (ex-info (str " key " k " not present") {})))
  (let [v (get m k)]
    (when (nil? v)
      (throw (ex-info (str "value of key " k " is nil") {})))
    v))

(defn get-in! [m ks]
  (let [[k & more] ks
        v (get! m k)]
    (if (seq more)
      (get-in! v more)
      v)))
