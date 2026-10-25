(ns com.budgetswu.app.cards-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.cards :as cards]))

(def uid #uuid "00000000-0000-0000-0000-000000000001")
(def row-id #uuid "00000000-0000-0000-0000-000000000002")

(deftest set-quantity
  (let [[state] (cards/set-quantity)
        ctx     {:biff.fx/random-uuid7-seq [row-id]}
        card    {:session/user     {:user/id uid}
                 :request/card     {:card/id "x"}
                 :request/quantity 2}]
    (is (= {:_      [:biff.sqlite.fx/authorized-write
                     {:insert-into   :owned-card
                      :values        [{:owned-card/id       row-id
                                       :owned-card/user-id  uid
                                       :owned-card/card-id  "x"
                                       :owned-card/quantity 2}]
                      :on-conflict   [:owned-card/user-id :owned-card/card-id]
                      :do-update-set [:owned-card/quantity]}]
            :status 204}
           (state ctx card)))
    (is (= [:biff.sqlite.fx/authorized-write
            {:delete-from :owned-card
             :where       [:and
                           [:= :owned-card/user-id uid]
                           [:= :owned-card/card-id "x"]]}]
           (:_ (state ctx (assoc card :request/quantity 0)))))))

(def tab-id #uuid "00000000-0000-0000-0000-000000000003")

(deftest set-search
  (let [[state] (cards/set-search)]
    (is (= {:_      [:biff.sqlite.fx/authorized-write
                     {:insert-into   :tab-state
                      :values        [{:tab-state/id          tab-id
                                       :tab-state/user-id     uid
                                       :tab-state/card-search "vader"
                                       :tab-state/card-page   1}]
                      :on-conflict   [:tab-state/id]
                      :do-update-set [:tab-state/card-search :tab-state/card-page]}]
            :status 204}
           (state {} {:session/user        {:user/id uid}
                      :request/tab-state   {:tab-state/id tab-id}
                      :request/card-search "vader"})))))

(deftest set-page
  (let [[state] (cards/set-page)]
    (is (= {:_      [:biff.sqlite.fx/authorized-write
                     {:insert-into   :tab-state
                      :values        [{:tab-state/id        tab-id
                                       :tab-state/user-id   uid
                                       :tab-state/card-page 3}]
                      :on-conflict   [:tab-state/id]
                      :do-update-set [:tab-state/card-page]}]
            :status 204}
           (state {} {:session/user      {:user/id uid}
                      :request/tab-state {:tab-state/id tab-id}
                      :request/page      3})))))
