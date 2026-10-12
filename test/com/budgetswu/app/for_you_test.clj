(ns com.budgetswu.app.for-you-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.for-you :as for-you]
            [com.budgetswu.routes :as routes]))

(deftest default-page
  (let [[state] (for-you/default-page)]
    (is (= (routes/cards)
           (get-in (state {} {:session/user {:user/owned-cards []}})
                   [:headers "location"])))
    (is (= (routes/for-you)
           (get-in (state {} {:session/user
                              {:user/owned-cards [{:owned-card/id 1}]}})
                   [:headers "location"])))))
