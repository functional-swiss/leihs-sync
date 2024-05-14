(ns funswiss.leihs-sync.scratch
  (:require
   [clojure.pprint :refer [pprint]]))

(comment
  (apply (partial (eval (read-string "(fn [x] (+ x 1))"))) [5])

  (apply (partial (eval (:inc (read-string "{:inc #(+ 1 %)}")))) [1])

  (let [inc2 (eval (:inc (read-string "{:inc #(+ 1 %)}")))]
    (inc2 5))

  (let [m (read-string "{:inc #(+ % 1)}")
        f (:inc m)]
    (eval (f 1))
    ;(with-out-str (pprint f))
    ))
(apply (eval (read-string "#(inc %)")) [1])

;(with-out-str (pprint {:x 5 :y 7}))
