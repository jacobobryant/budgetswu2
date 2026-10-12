(ns com.budgetswu.model.global-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.model.global :as recommendation]))

(deftest placement-percentile
  (is (= 9/10 (recommendation/placement-percentile 1 10)))
  (is (= 19/20 (recommendation/placement-percentile 1 20)))
  (is (nil? (recommendation/placement-percentile nil 20)))
  (is (nil? (recommendation/placement-percentile 21 20))))

(deftest deck-price
  (is (= 650
         (recommendation/deck-price
          [{:deck-card/quantity 3 :card-price/cents 200}
           {:deck-card/quantity 1 :card-price/cents 50}])))
  (is (nil? (recommendation/deck-price
             [{:deck-card/quantity 1 :card-price/cents nil}]))))

(deftest archetypes
  (let [row        (fn [id leader aspect]
                     {:decklist/id          id
                      :decklist/leader-name leader
                      :card-aspect/aspect   aspect})
        rows       [(row "a" "Luke" "Aggression")
                    (row "a" "Luke" "Aggression")
                    (row "b" "Luke" "Aggression")
                    (row "c" "Han" nil)
                    (row "d" "Leia" "Command")
                    (row "e" nil nil)]
        archetypes (recommendation/deck-archetypes rows)
        popularity (recommendation/archetype-popularity (vals archetypes))]
    (testing "bases of the same color share an archetype"
      (is (= ["Luke" #{"Aggression"}] (archetypes "a") (archetypes "b")))
      (is (= ["Han" #{}] (archetypes "c")))
      (is (nil? (archetypes "e"))))
    (testing "ties share a competition ranking"
      (is (= {:recommendation/archetype-frequency 2
              :recommendation/popularity-rank     1
              :recommendation/archetype-count     3}
             (popularity ["Luke" #{"Aggression"}])))
      (is (= [2 2]
             (mapv (comp :recommendation/popularity-rank popularity)
                   [["Han" #{}] ["Leia" #{"Command"}]]))))))

(deftest candidate
  (let [deck    {:decklist/id              "deck"
                 :decklist/leader-name     "Luke"
                 :tournament/player-count  20
                 :standing/final-placement 1}
        cards   [{:deck-card/quantity 50 :card-price/cents 200}]
        popular {:recommendation/archetype-frequency 1}]
    (is (= {:recommendation/id           "deck"
            :recommendation/leader-name  "Luke"
            :recommendation/player-count 20
            :recommendation/placement    1
            :recommendation/price-cents  10000
            :recommendation/percentile   19/20}
           (select-keys (recommendation/candidate deck cards popular)
                        [:recommendation/id :recommendation/leader-name
                         :recommendation/player-count
                         :recommendation/placement
                         :recommendation/price-cents
                         :recommendation/percentile])))
    (is (nil? (recommendation/candidate deck [] popular)))
    (is (nil? (recommendation/candidate deck cards nil)))
    (is (nil? (recommendation/candidate
               deck [{:deck-card/quantity 2 :card-price/cents 1}] popular)))))

(defn- option [id price percentile frequency]
  {:recommendation/id                  id
   :recommendation/price-cents         price
   :recommendation/percentile          percentile
   :recommendation/archetype-frequency frequency})

(deftest pareto-front
  (let [cheap    (option "a" 100 1/2 5)
        better   (option "b" 100 3/4 5)
        equal    (option "c" 100 3/4 5)
        rare     (option "d" 100 1/2 1)
        stronger (option "e" 200 9/10 5)
        worse    (option "f" 300 1/2 5)]
    (testing "equal price and popularity keep only the better placement"
      (is (recommendation/dominates? better cheap)))
    (testing "a less popular archetype is not dominated"
      (is (not (recommendation/dominates? better rare))))
    (testing "ordered by price, then popularity, then ID"
      (is (= ["d" "b" "c" "e"]
             (mapv :recommendation/id
                   (recommendation/pareto-front
                    [worse equal stronger cheap better rare])))))))

(defn- resolve-with [resolver rows-by-table]
  ((:biff.graph/resolve-fn resolver)
   {:biff.graph/input {}

    :biff.fx/handlers
    {:biff.sqlite.fx/execute
     (fn [_ statement]
       (get rows-by-table (:from statement) []))}}))

(deftest list-resolvers
  (let [cases [[recommendation/sync-states :sync-state
                :global/sync-states {:sync-state/id "job"}]
               [recommendation/metas :meta
                :global/metas {:meta/id "HMW" :meta/is-current true}]
               [recommendation/tournaments :tournament
                :global/tournaments {:tournament/id "event"}]
               [recommendation/decklists :decklist
                :global/decklists {:decklist/id "deck"}]
               [recommendation/leaders-and-bases :card
                :global/leaders-and-bases
                {:card/collector-number "SOR_022" :card/type "Base"}]
               [recommendation/card-sets :card
                :global/card-sets {:card/set-code "SOR"}]]]
    (doseq [[resolver table output-key row] cases]
      (is (= [row]
             (get (resolve-with resolver {table [row]}) output-key))))))

(deftest recommendation-resolvers
  (let [deck {:decklist/id              "deck"
              :tournament/player-count  20
              :standing/final-placement 1}
        card {:deck-card/decklist-id "deck"
              :deck-card/quantity    50
              :card-price/cents      100}
        rows {:decklist       [(assoc deck :decklist/leader-name "Luke")]
              :deck-card      [card]
              :recommendation [{:recommendation/id   "deck"
                                :recommendation/rank 0}]}]
    (is (= ["deck"]
           (mapv :recommendation/id
                 (:global/front
                  (resolve-with recommendation/front rows)))))
    (is (= ["deck"]
           (mapv :recommendation/id
                 (:global/recommendations
                  (resolve-with recommendation/stored rows)))))))
