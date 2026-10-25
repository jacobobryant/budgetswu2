(ns com.budgetswu.lib.ui-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.lib.ui :as ui]))

(def uid #uuid "00000000-0000-0000-0000-000000000001")

(deftest admin?
  (is (ui/admin? {:session {:uid uid} :biff.admin/admin-user-id (str uid)}))
  (is (not (ui/admin? {:session {:uid uid}})))
  (is (not (ui/admin? {:session {:uid uid} :biff.admin/admin-user-id "someone-else"})))
  (testing "signed-out visitors aren't the admin even when no admin is configured"
    (is (not (ui/admin? {:session {} :biff.admin/admin-user-id ""})))))
