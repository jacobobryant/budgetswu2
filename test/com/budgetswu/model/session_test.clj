(ns com.budgetswu.model.session-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.model.session :as session]))

(deftest user
  (let [uid     #uuid "00000000-0000-0000-0000-000000000001"
        resolve #((:biff.graph/resolve-fn session/user) {:biff.graph/input {} :session %})]
    (is (= {:session/user {:user/id uid}} (resolve {:uid uid})))
    (is (nil? (resolve {})))))
