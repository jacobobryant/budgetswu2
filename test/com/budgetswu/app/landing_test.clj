(ns com.budgetswu.app.landing-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.landing :as landing]))

(deftest wrap-redirect-signed-in
  (let [handler (landing/wrap-redirect-signed-in (constantly {:status 200}))]
    (is (= {:status 200} (handler {:session {}})))
    (is (= {:status 303 :headers {"location" "/app"}}
           (handler {:session {:uid 1}})))
    (is (= {:status 200}
           (handler {:session {:uid 1} :params {:redirect "false"}})))))
