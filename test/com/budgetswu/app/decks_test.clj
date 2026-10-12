(ns com.budgetswu.app.decks-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.decks :as decks]
            [com.budgetswu.routes :as routes]))

(deftest default-page
  (let [[state] (decks/default-page)]
    (is (= (routes/cards)
           (get-in (state {} {:session/user {:user/owned-cards []}})
                   [:headers "location"])))
    (is (= (routes/decks)
           (get-in (state {} {:session/user
                              {:user/owned-cards [{:owned-card/id 1}]}})
                   [:headers "location"])))))

(deftest decks-page
  (let [[state] (decks/decks-page)

        uid  #uuid "00000000-0000-0000-0000-000000000001"
        user {:user/id              uid
              :user/recommendations []
              :user/owned-cards     []}
        page (fn [seen]
               (state {:biff.datastar/sse-request true}
                      {:session/user (assoc user :user/seen-recommendations seen)}))]
    (is (= [:biff.sqlite.fx/authorized-write
            {:update :user
             :set    {:user/seen-recommendations [:lift #{}]}
             :where  [:= :user/id uid]}]
           (:_ (page #{"b"}))))
    (is (some? (:_ (page nil))))
    (is (nil? (:_ (page #{}))))
    (is (= 200 (:status (page #{}))))))
