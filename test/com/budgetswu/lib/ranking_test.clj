(ns com.budgetswu.lib.ranking-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.lib.ranking :as ranking]))

(deftest placement-percentile
  (is (= 9/10 (ranking/placement-percentile 1 10)))
  (is (= 19/20 (ranking/placement-percentile 1 20)))
  (is (nil? (ranking/placement-percentile nil 20)))
  (is (nil? (ranking/placement-percentile 21 20))))

(deftest deck-prices
  (let [cards [{:deck-card/card-id "a" :deck-card/quantity 3 :card/price-cents 200}
               {:deck-card/card-id "b" :deck-card/quantity 1 :card/price-cents 50}
               {:deck-card/card-id "b" :deck-card/quantity 1 :card/price-cents 50}]]
    (is (= {:full 700 :remaining 700} (ranking/deck-prices cards {})))
    (testing "owned copies are subtracted per card across printings"
      (is (= {:full 700 :remaining 200}
             (ranking/deck-prices cards {"a" 2 "b" 3}))))
    (is (nil? (ranking/deck-prices
               [{:deck-card/card-id "a" :deck-card/quantity 1}]
               {})))))

(deftest archetypes
  (let [row        (fn [id leader aspect]
                     {:decklist/id        id
                      :decklist/leader-id leader
                      :card-aspect/aspect aspect})
        rows       [(row "a" "Luke" "Aggression")
                    (row "a" "Luke" "Aggression")
                    (row "b" "Luke" "Aggression")
                    (row "c" "Han" nil)
                    (row "d" "Leia" "Command")
                    (row "e" nil nil)]
        archetypes (ranking/deck-archetypes rows)
        popularity (ranking/archetype-popularity (vals archetypes))]
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
                 :decklist/leader-id       "Luke"
                 :tournament/player-count  20
                 :standing/final-placement 1}
        cards   [{:deck-card/card-id  "x"
                  :deck-card/quantity 50
                  :card/price-cents   200}]
        popular {:recommendation/archetype-frequency 1}]
    (is (= {:recommendation/id               "deck"
            :recommendation/leader-name      "Luke"
            :recommendation/player-count     20
            :recommendation/placement        1
            :recommendation/price-cents      9000
            :recommendation/full-price-cents 10000
            :recommendation/percentile       19/20}
           (select-keys (ranking/candidate deck cards popular {"x" 5})
                        [:recommendation/id
                         :recommendation/leader-name
                         :recommendation/player-count
                         :recommendation/placement
                         :recommendation/price-cents
                         :recommendation/full-price-cents
                         :recommendation/percentile])))
    (is (nil? (ranking/candidate deck [] popular {})))
    (is (nil? (ranking/candidate deck cards nil {})))
    (is (nil? (ranking/candidate
               deck
               [{:deck-card/card-id "x" :deck-card/quantity 2 :card/price-cents 1}]
               popular
               {})))))

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
      (is (ranking/dominates? better cheap)))
    (testing "a less popular archetype is not dominated"
      (is (not (ranking/dominates? better rare))))
    (testing "ordered by price, then popularity, then ID"
      (is (= ["d" "b" "c" "e"]
             (mapv :recommendation/id
                   (ranking/pareto-front
                    [worse equal stronger cheap better rare])))))))
