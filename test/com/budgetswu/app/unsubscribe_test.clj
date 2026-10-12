(ns com.budgetswu.app.unsubscribe-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.unsubscribe :as unsubscribe]))

(deftest unsubscribe
  (let [[state] (unsubscribe/unsubscribe)

        uid #uuid "00000000-0000-0000-0000-000000000001"
        now #inst "2026-10-12T00:00:00Z"]
    (is (= {:_      [:biff.sqlite.fx/execute
                     {:update :user
                      :set    {:user/unsubscribed-at now}
                      :where  [:and
                               [:= :user/id uid]
                               [:= :user/unsubscribed-at nil]]}]
            :status 204}
           (state {:biff.fx/now now}
                  {:request/notification {:notification/user {:user/id uid}}})))
    (is (= {:status 404} (state {:biff.fx/now now} {})))))
