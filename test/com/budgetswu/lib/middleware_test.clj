(ns com.budgetswu.lib.middleware-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.lib.middleware :as mid]))

(deftest wrap-redirect-signed-in
  (let [handler (mid/wrap-redirect-signed-in (constantly {:status 200}))]
    (is (= {:status 200} (handler {:session {}})))
    (is (= {:status 303 :headers {"location" "/app"}}
           (handler {:session {:uid 1}})))
    (is (= {:status 200}
           (handler {:session {:uid 1} :params {:redirect "false"}})))))
