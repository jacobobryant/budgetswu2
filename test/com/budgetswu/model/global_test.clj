(ns com.budgetswu.model.global-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.model.global :as global]))

(defn- resolve-with [resolver rows-by-table]
  ((:biff.graph/resolve-fn resolver)
   {:biff.graph/input {}

    :biff.fx/handlers
    {:biff.sqlite.fx/execute
     (fn [_ statement]
       (get rows-by-table (:from statement) []))}}))

(deftest list-resolvers
  (let [cases [[global/sync-states :sync-state
                :global/sync-states {:sync-state/id "job"}]
               [global/metas :meta
                :global/metas {:meta/id "HMW" :meta/is-current true}]
               [global/tournaments :tournament
                :global/tournaments {:tournament/id "event"}]
               [global/card-sets :card-set
                :global/card-sets {:card-set/id           "SOR"
                                   :card-set/number       1
                                   :card-set/published-on "2024-03-08"}]
               [global/standard-printings :printing
                :global/standard-printings
                {:printing/card-id          "Lake Country"
                 :printing/collector-number "HMW_023"}]
               [global/home-decklists :recommendation
                :global/home-decklists {:recommendation/id "deck"}]
               [global/notification-candidates :user
                :global/notification-candidates {:user/id "user"}]]]
    (doseq [[resolver table output-key row] cases]
      (is (= [row]
             (get (resolve-with resolver {table [row]}) output-key))))))

(deftest recommendation-resolvers
  (let [deck {:decklist/id             "deck"
              :decklist/leader-id      "Luke"
              :decklist/placement      1
              :tournament/player-count 20}
        card {:deck-card/decklist-id "deck"
              :deck-card/card-id     "x"
              :deck-card/quantity    50
              :card/price-cents      100}
        rows {:decklist       [deck]
              :deck-card      [card]
              :recommendation [{:recommendation/id   "deck"
                                :recommendation/rank 0}]}]
    (is (= ["deck"]
           (mapv :recommendation/id
                 (:global/front (resolve-with global/front rows)))))
    (is (= ["deck"]
           (mapv :recommendation/id
                 (:global/recommendations
                  (resolve-with global/stored rows)))))))
