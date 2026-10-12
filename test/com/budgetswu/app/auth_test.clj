(ns com.budgetswu.app.auth-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.auth :as auth]))

(deftest create-user
  (let [[query insert result] (auth/create-user)

        uid #uuid "00000000-0000-0000-0000-000000000001"
        now #inst "2026-10-12T00:00:00Z"]
    (is (= {:email "a@example.com"
            :home  [:biff.graph.fx/query
                    [{:global/home-decklists [:recommendation/id]}]]}
           (query {} {:email "a@example.com"})))
    (is (= [:biff.sqlite.fx/execute
            {:insert-into   :user
             :values        [{:user/id                   uid
                              :user/email                "a@example.com"
                              :user/joined-at            now
                              :user/seen-recommendations [:lift #{"a" "b"}]}]
             :on-conflict   [:user/email]
             :do-update-set [:user/email]
             :returning     [:user/id]}]
           (insert {:biff.fx/now now :biff.fx/random-uuid7-seq [uid]}
                   {:email "a@example.com"
                    :home  {:global/home-decklists [{:recommendation/id "a"}
                                                    {:recommendation/id "b"}]}})))
    (is (= uid (result {} [{:user/id uid}])))))
