(ns funswiss.aad-leihs-sync.utils.obscurity
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.string :as string]
    [clojure.tools.logging :as logging]
    [crypto.random :as cr]
    [funswiss.aad-leihs-sync.utils.core :refer [keyword presence str]]
    [taoensso.timbre :as timbre :refer [debug info]]
    )
  (:import java.util.Base64)
  )




(def SIZE 256)

(defn _rba [] (cr/bytes SIZE))

(def rba (memoize _rba))

(defn rbs []
  (->> (iterate inc 0)
       (map #(mod % SIZE))
       (map #(get (rba) %))))

(defn byte-xor [b1 b2]
  (unchecked-byte
    (bit-xor (Byte/toUnsignedLong b1)
             (Byte/toUnsignedLong b2))))

(defn seq-xor [xs]
  (loop [xs xs
         xr (rbs)
         res []]
    (if-let [s (first xs)]
      (recur (rest xs)
             (rest xr)
             (conj res (byte-xor s (first xr))))
      res)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn encode-b64 [to-encode]
  (.encodeToString (Base64/getEncoder) to-encode))

(defn decode-b64 [to-decode]
  (.decode (Base64/getDecoder) to-decode))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn encrypt [s]
  "Encrypt some internally used string spcifically to make it unreadable in logs etc.
  Based on xor-ing over finite(!) number of random bytes. The first chars before the
  last \":\" are the ones of the original string."
  (str (subs s 0 (min 5 (-> s .length (/ 2) Math/floor int))) ":"
       (-> s .getBytes seq-xor byte-array encode-b64)))

(defn decrypt [xs]
  "See encrypt in the same namespace."
  (->> (-> xs (string/split #":"))
       last
       decode-b64
       (seq-xor)
       (map char)
       (apply str)))
