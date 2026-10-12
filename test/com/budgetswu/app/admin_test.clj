(ns com.budgetswu.app.admin-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.app.admin :as admin]
            [com.budgetswu.lib.middleware :as mid]
            [com.budgetswu.lib.notification-test :refer [deck]]
            [com.budgetswu.lib.ui :as ui]))

(def uid #uuid "00000000-0000-0000-0000-000000000001")

(deftest admin?
  (is (ui/admin? {:session {:uid uid} :biff.admin/admin-user-id (str uid)}))
  (is (not (ui/admin? {:session {:uid uid}})))
  (is (not (ui/admin? {:session {:uid uid} :biff.admin/admin-user-id "someone-else"})))
  (testing "signed-out visitors aren't the admin even when no admin is configured"
    (is (not (ui/admin? {:session {} :biff.admin/admin-user-id ""})))))

(deftest wrap-admin
  (let [handler (mid/wrap-admin (constantly {:status 200}))]
    (is (= 200 (:status (handler {:session                  {:uid uid}
                                  :biff.admin/admin-user-id (str uid)}))))
    (is (= 404 (:status (handler {:session {:uid uid}}))))))

(deftest send-test-email
  (let [[decide send-email respond] (admin/send-test-email)

        nid   #uuid "00000000-0000-0000-0000-000000000002"
        token #uuid "00000000-0000-0000-0000-000000000009"
        now   #inst "2026-10-12T00:00:00Z"
        ctx   {:com.budgetswu/base-url        "https://example.com"
               :com.budgetswu/mailing-address "1 Main St"
               :biff.fx/now                   now
               :biff.fx/random-uuid7-seq      [nid]
               :biff.fx/random-uuid4-seq      [token]}
        user  {:user/id              uid
               :user/email           "admin@example.com"
               :user/recommendations [(deck "a" "Luke") (deck "b" "Leia") (deck "c" "Han")]}]
    (testing "nothing is sent without a selection"
      (is (= {:biff.fx/return {:status 400}}
             (decide ctx {:session/user user :request/selected-recommendations #{""}})))
      (is (= {:biff.fx/return {:status 400}}
             (decide ctx {:session/user user}))))
    (testing "the selected decks are the email's new decks"
      (let [result (decide ctx {:session/user                     user
                                :request/selected-recommendations #{"" "a" "c"}})]
        (is (= [:biff.sqlite.fx/execute
                {:insert-into :notification
                 :values      [{:notification/id         nid
                                :notification/user-id    uid
                                :notification/created-at now
                                :notification/token      token}]}]
               (:_ result)))
        (is (= "New decks: Luke / Lake Country and 1 other"
               (get-in result [:email :subject])))
        (is (= [:com.budgetswu.fx/send-email (:email result)]
               (:_ (send-email ctx (dissoc result :_)))))
        (testing "the selection is cleared, keeping one slot per checkbox"
          (is (= 200 (:status (respond ctx result))))
          (is (re-find #"\"admin_selected\":\[\"\",\"\",\"\"\]"
                       (str (:body (respond ctx result))))))))))
