(ns com.budgetswu.app.cards-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.cards :as cards]))

(def uid #uuid "00000000-0000-0000-0000-000000000001")
(def row-id #uuid "00000000-0000-0000-0000-000000000002")

(deftest set-quantity
  (let [[state] (cards/set-quantity)
        ctx     {:session {:uid uid} :biff.fx/random-uuid7-seq [row-id]}
        card    {:request/card {:card/id "x"} :request/quantity 2}]
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
    (is (= {:status 400} (state ctx (dissoc card :request/quantity))))
    (is (= {:status 400} (state ctx (dissoc card :request/card))))))

(def tab-id #uuid "00000000-0000-0000-0000-000000000003")

(deftest set-search
  (let [[state] (cards/set-search)
        ctx     {:biff.datastar/tab-id tab-id}]
    (is (= {:_      [:biff.sqlite.fx/authorized-write
                     {:insert-into   :tab-state
                      :values        [{:tab-state/id          tab-id
                                       :tab-state/card-search "vader"
                                       :tab-state/card-page   1}]
                      :on-conflict   [:tab-state/id]
                      :do-update-set [:tab-state/card-search :tab-state/card-page]}]
            :status 204}
           (state ctx {:request/card-search "vader"})))
    (is (= {:status 400} (state ctx {})))
    (is (= {:status 400} (state {} {:request/card-search "vader"})))))

(deftest set-page
  (let [[state] (cards/set-page)
        ctx     {:biff.datastar/tab-id tab-id}]
    (is (= {:_      [:biff.sqlite.fx/authorized-write
                     {:insert-into   :tab-state
                      :values        [{:tab-state/id        tab-id
                                       :tab-state/card-page 3}]
                      :on-conflict   [:tab-state/id]
                      :do-update-set [:tab-state/card-page]}]
            :status 204}
           (state ctx {:request/page 3})))
    (is (= {:status 400} (state ctx {})))))
