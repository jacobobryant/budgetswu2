(ns com.budgetswu.model.recommendation-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.model.recommendation :as recommendation]))

(defn- resolve-with [resolver inputs rows]
  ((:biff.graph/resolve-fn resolver)
   {:biff.graph/input inputs
    :biff.fx/handlers {:biff.sqlite.fx/execute (fn [_ _] rows)}}))

(deftest placement-percentile
  (is (= 9/10 (recommendation/placement-percentile 1 10)))
  (is (= 19/20 (recommendation/placement-percentile 1 20)))
  (is (nil? (recommendation/placement-percentile nil 20)))
  (is (nil? (recommendation/placement-percentile 21 20))))

(deftest deck-prices
  (let [cards [{:deck-card/card-id "a" :deck-card/quantity 3 :card/price-cents 200}
               {:deck-card/card-id "b" :deck-card/quantity 1 :card/price-cents 50}
               {:deck-card/card-id "b" :deck-card/quantity 1 :card/price-cents 50}]]
    (is (= {:recommendation/full-price-cents 700 :recommendation/price-cents 700}
           (recommendation/deck-prices cards {})))
    (testing "owned copies are subtracted per card across printings"
      (is (= {:recommendation/full-price-cents 700 :recommendation/price-cents 200}
             (recommendation/deck-prices cards {"a" 2 "b" 3}))))
    (is (nil? (recommendation/deck-prices
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

(deftest details
  (is (= [{:recommendation/melee-id        "deck"
           :recommendation/leader-name     "Luke"
           :recommendation/base-name       "Lake Country"
           :recommendation/player-name     "Player"
           :recommendation/tournament-name "Showdown"
           :recommendation/tournament-date "2026-10-10T00:00:00Z"
           :recommendation/player-count    20
           :recommendation/placement       1
           :recommendation/percentile      19/20}
          {}]
         (resolve-with recommendation/details
                       [{:recommendation/id "deck"} {:recommendation/id "gone"}]
                       [{:decklist/id             "deck"
                         :decklist/leader-id      "Luke"
                         :decklist/base-id        "Lake Country"
                         :decklist/player-name    "Player"
                         :decklist/placement      1
                         :tournament/name         "Showdown"
                         :tournament/held-at      "2026-10-10T00:00:00Z"
                         :tournament/player-count 20}]))))

(deftest label
  (is (= {:recommendation/label "Luke / Lake Country"}
         (resolve-with recommendation/label
                       {:recommendation/leader-name "Luke"
                        :recommendation/base-name   "Lake Country"}
                       [])))
  (is (= {:recommendation/label "Unknown leader / Unknown base"}
         (resolve-with recommendation/label {} []))))

(deftest melee-url
  (is (= {:recommendation/melee-url "https://melee.gg/Decklist/View/deck"}
         (resolve-with recommendation/melee-url {:recommendation/melee-id "deck"} []))))

(deftest tournament-day
  (is (= {:recommendation/tournament-day "2026-10-10"}
         (resolve-with recommendation/tournament-day
                       {:recommendation/tournament-date "2026-10-10T23:00:00Z"}
                       []))))

(deftest popularity
  (is (= [{:recommendation/archetype-frequency 2
           :recommendation/popularity-rank     1
           :recommendation/archetype-count     1}
          {}]
         (resolve-with recommendation/popularity
                       [{:recommendation/id "a"} {:recommendation/id "old"}]
                       (mapv (fn [id]
                               {:decklist/id        id
                                :decklist/leader-id "Luke"
                                :card-aspect/aspect "Command"})
                             ["a" "b"])))))

(deftest prices
  (let [cards [{:deck-card/decklist-id "a"
                :deck-card/card-id     "x"
                :deck-card/quantity    48
                :card/price-cents      100}
               {:deck-card/decklist-id "a"
                :deck-card/card-id     "y"
                :deck-card/quantity    2
                :card/price-cents      300}
               {:deck-card/decklist-id "b"
                :deck-card/card-id     "z"
                :deck-card/quantity    50}]
        user  {:user/owned-cards [{:owned-card/card-id "y" :owned-card/quantity 1}]}]
    (is (= [{:recommendation/card-count       50
             :recommendation/price-cents      5100
             :recommendation/full-price-cents 5400}
            {:recommendation/card-count       50
             :recommendation/price-cents      5400
             :recommendation/full-price-cents 5400}
            {:recommendation/card-count 50}
            {:recommendation/card-count 0}]
           (resolve-with recommendation/prices
                         [{:recommendation/id "a" :recommendation/user user}
                          {:recommendation/id "a"}
                          {:recommendation/id "b"}
                          {:recommendation/id "empty"}]
                         cards)))))

(deftest eligible
  (let [resolve-eligible #(resolve-with recommendation/eligible {:recommendation/card-count %} [])]
    (is (= {:recommendation/eligible true} (resolve-eligible 50)))
    (is (= {:recommendation/eligible false} (resolve-eligible 49)))))
