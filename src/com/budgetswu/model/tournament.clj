(ns com.budgetswu.model.tournament
  (:require [com.biffweb.graph :refer [defresolver]]
            [tick.core :as tick]))

;; The UTC date of the tournament's start time.
(defresolver held-on
  {:input  [:tournament/held-at]
   :output [:tournament/held-on]}
  [_ {:tournament/keys [held-at]}]
  {:tournament/held-on (-> held-at tick/instant (tick/in "UTC") tick/date str)})

(def module
  {:biff.graph/resolvers [held-on]})
