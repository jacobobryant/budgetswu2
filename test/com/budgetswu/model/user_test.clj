(ns com.budgetswu.model.user-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.model.user :as user]))

(defn- resolve-with [resolver ctx rows-by-table]
  ((:biff.graph/resolve-fn resolver)
   (merge
    {:biff.graph/input {:user/id #uuid "00000000-0000-0000-0000-000000000001"}

     :biff.fx/handlers
     {:biff.sqlite.fx/execute
      (fn [_ statement]
        (get rows-by-table (:from statement) []))}}
    ctx)))

(deftest session-user
  (let [uid #uuid "00000000-0000-0000-0000-000000000001"]
    (is (= {:session/user {:user/id uid}}
           (resolve-with user/session-user {:session {:uid uid}} {})))
    (is (nil? (resolve-with user/session-user {:session {}} {})))))

(def uid-1 #uuid "00000000-0000-0000-0000-000000000001")
(def uid-2 #uuid "00000000-0000-0000-0000-000000000002")

(deftest owned-cards
  (let [row {:owned-card/id       #uuid "00000000-0000-0000-0000-000000000003"
             :owned-card/user-id  uid-2
             :owned-card/card-id  "x"
             :owned-card/quantity 2}]
    (is (= [{:user/owned-cards []}
            {:user/owned-cards [(dissoc row :owned-card/user-id)]}]
           (resolve-with user/owned-cards
                         {:biff.graph/input [{:user/id uid-1} {:user/id uid-2}]}
                         {:owned-card [row]})))))

(deftest recommendations
  (let [rows  {:decklist  [{:decklist/id             "deck"
                            :decklist/leader-id      "Luke"
                            :decklist/placement      1
                            :tournament/player-count 20}]
               :deck-card [{:deck-card/decklist-id "deck"
                            :deck-card/card-id     "x"
                            :deck-card/quantity    50
                            :card/price-cents      100}]}
        input {:user/owned-cards [{:owned-card/card-id "x" :owned-card/quantity 3}]}]
    (is (= [{:price 4700 :full 5000}]
           (mapv (fn [r]
                   {:price (:recommendation/price-cents r)
                    :full  (:recommendation/full-price-cents r)})
                 (:user/recommendations
                  (resolve-with user/recommendations {:biff.graph/input input} rows)))))))
