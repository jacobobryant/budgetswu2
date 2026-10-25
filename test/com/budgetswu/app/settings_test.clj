(ns com.budgetswu.app.settings-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.settings :as settings]))

(deftest set-notifications
  (let [[state] (settings/set-notifications)

        uid     #uuid "00000000-0000-0000-0000-000000000001"
        now     #inst "2026-10-12T00:00:00Z"
        ctx     {:session {:uid uid} :biff.fx/now now}
        written (fn [value]
                  {:_      [:biff.sqlite.fx/authorized-write
                            {:update :user
                             :set    {:user/unsubscribed-at value}
                             :where  [:= :user/id uid]}]
                   :status 204})]
    (is (= (written now) (state ctx {:request/email-notifications false})))
    (is (= (written nil) (state ctx {:request/email-notifications true})))))
