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
               [global/decklists :decklist
                :global/decklists {:decklist/id "deck"}]
               [global/leaders-and-bases :card
                :global/leaders-and-bases
                {:card/id "Lake Country" :card/type "Base"}]
               [global/card-sets :card-set
                :global/card-sets {:card-set/id "SOR"}]
               [global/standard-printings :printing
                :global/standard-printings
                {:printing/card-id          "Lake Country"
                 :printing/collector-number "HMW_023"}]]]
    (doseq [[resolver table output-key row] cases]
      (is (= [row]
             (get (resolve-with resolver {table [row]}) output-key))))))

(deftest recommendation-resolvers
  (let [deck {:decklist/id              "deck"
              :decklist/leader-id       "Luke"
              :tournament/player-count  20
              :standing/final-placement 1}
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
