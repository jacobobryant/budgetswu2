(ns com.budgetswu.lib.notification-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.budgetswu.lib.notification :as notification]))

(defn deck [id leader]
  {:recommendation/id               id
   :recommendation/melee-id         (str "melee-" id)
   :recommendation/leader-name      leader
   :recommendation/base-name        "Lake Country"
   :recommendation/player-name      "Player"
   :recommendation/tournament-name  "Showdown"
   :recommendation/tournament-date  "2026-10-10T00:00:00Z"
   :recommendation/player-count     9
   :recommendation/placement        7
   :recommendation/price-cents      627
   :recommendation/full-price-cents 789
   :recommendation/popularity-rank  19
   :recommendation/archetype-count  46})

(deftest subject
  (is (= "New deck: Luke / Lake Country"
         (notification/subject [(deck "a" "Luke")])))
  (is (= "New decks: Luke / Lake Country and 1 other"
         (notification/subject [(deck "a" "Luke") (deck "b" "Leia")])))
  (is (= "New decks: Luke / Lake Country and 2 others"
         (notification/subject [(deck "a" "Luke") (deck "b" "Leia") (deck "c" "Han")])))
  (is (= "New deck: Unknown leader / Unknown base"
         (notification/subject [{}]))))

(def ctx
  {:com.budgetswu/base-url        "https://example.com/"
   :com.budgetswu/mailing-address "1 Main St"})

(def token #uuid "00000000-0000-0000-0000-000000000009")

(deftest email
  (let [decks [(deck "a" "Luke") (deck "b" "Leia")]

        {:keys [to subject text html]}
        (notification/email ctx "a@example.com" decks [(second decks)] token)]
    (is (= "a@example.com" to))
    (is (= "New deck: Leia / Lake Country" subject))
    (testing "new decks are numbered by their place in the full list"
      (is (str/includes? text "2. Leia / Lake Country"))
      (is (not (str/includes? text "Luke"))))
    (is (str/includes? text "Your cost: $6.27 (full $7.89)"))
    (is (str/includes? text "https://example.com/decks"))
    (is (str/includes? text "https://example.com/unsubscribe/"))
    (is (str/includes? text "1 Main St"))
    (is (str/includes? html "https://melee.gg/Decklist/View/melee-b"))
    (is (str/includes? html "See all your recommendations"))))

(deftest insert
  (let [uid #uuid "00000000-0000-0000-0000-000000000001"
        nid #uuid "00000000-0000-0000-0000-000000000002"
        now #inst "2026-10-12T00:00:00Z"]
    (is (= {:insert-into :notification
            :values      [{:notification/id         nid
                           :notification/user-id    uid
                           :notification/created-at now
                           :notification/token      token}]}
           (notification/insert {:biff.fx/now now :biff.fx/random-uuid7-seq [nid]}
                                uid
                                token)))))
