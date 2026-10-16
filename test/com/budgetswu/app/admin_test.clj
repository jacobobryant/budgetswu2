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

(def tab-id #uuid "00000000-0000-0000-0000-000000000003")

(defn- tab-state-write [fields]
  [:biff.sqlite.fx/authorized-write
   {:insert-into   :tab-state
    :values        [(merge {:tab-state/id tab-id :tab-state/user-id uid} fields)]
    :on-conflict   [:tab-state/id]
    :do-update-set (vec (keys fields))}])

(deftest select-deck
  (let [[state] (admin/select-deck)

        ctx   {:session {:uid uid}}
        input {:request/decklist  {:decklist/id "b"}
               :request/selected  true
               :request/tab-state {:tab-state/id tab-id :tab-state/admin-selected #{"a"}}}]
    (is (= {:_      (tab-state-write {:tab-state/admin-selected [:lift #{"a" "b"}]
                                      :tab-state/admin-status   nil})
            :status 204}
           (state ctx input)))
    (is (= (tab-state-write {:tab-state/admin-selected [:lift #{}]
                             :tab-state/admin-status   nil})
           (:_ (state ctx (assoc input
                                 :request/decklist {:decklist/id "a"}
                                 :request/selected false)))))
    (is (= {:status 400} (state ctx (dissoc input :request/decklist))))
    (is (= {:status 400} (state ctx (dissoc input :request/selected))))
    (is (= {:status 400} (state ctx (dissoc input :request/tab-state))))))

(deftest send-test-email
  (let [[decide send-email save] (admin/send-test-email)

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
               :user/recommendations [(deck "a" "Luke") (deck "b" "Leia") (deck "c" "Han")]}
        tab   (fn [selected]
                {:tab-state/id tab-id :tab-state/admin-selected selected})]
    (testing "nothing is sent without a selection"
      (is (= {:biff.fx/return {:status 400}}
             (decide ctx {:session/user user :request/tab-state (tab #{})})))
      (is (= {:biff.fx/return {:status 400}}
             (decide ctx {:session/user user :request/tab-state {:tab-state/id tab-id}})))
      (is (= {:biff.fx/return {:status 400}}
             (decide ctx {:session/user user}))))
    (testing "the selected decks are the email's new decks"
      (let [result (decide ctx {:session/user user :request/tab-state (tab #{"a" "c"})})]
        (is (= [:biff.sqlite.fx/authorized-write
                {:insert-into :notification
                 :values      [{:notification/id         nid
                                :notification/user-id    uid
                                :notification/created-at now
                                :notification/token      token}]}]
               (:_ result)))
        (is (= "New decks: Luke / Lake Country and 1 other"
               (get-in result [:email :subject])))
        (is (= [uid tab-id] ((juxt :user-id :tab-id) result)))
        (is (= [:com.budgetswu.fx/log :info]
               (subvec (:response (send-email ctx (dissoc result :_))) 0 2)))
        (testing "the selection is cleared and the result shown in tab state"
          (is (= {:_      (tab-state-write {:tab-state/admin-selected [:lift #{}]
                                            :tab-state/admin-status   "Sent to admin@example.com."})
                  :status 204}
                 (save ctx (assoc result :response nil))))
          (is (= "Couldn't send to admin@example.com."
                 (get-in (save ctx (assoc result :response {:status 500}))
                         [:_ 1 :values 0 :tab-state/admin-status]))))))))

(deftest regenerate-home
  (let [[state] (admin/regenerate-home)

        ctx    {:session {:uid uid}}
        result (state ctx {:global/recommendations [(deck "a" "Luke") (deck "b" "Leia")]
                           :request/tab-state      {:tab-state/id tab-id}})]
    (is (= :com.budgetswu.fx/write-home-html (first (:_home result))))
    (is (re-find #"Leia" (second (:_home result))))
    (is (= (tab-state-write {:tab-state/home-status "Regenerated with 2 decks."})
           (:_status result)))
    (is (= 204 (:status result)))
    (is (= {:status 400} (state ctx {:global/recommendations []})))))

(deftest get-users
  (let [[state] (admin/get-users)
        now     #inst "2026-10-12T00:00:00Z"]
    (is (= [{:user-id uid :email "a@example.com" :joined-at now}]
           (state {} {:global/users [{:user/id        uid
                                      :user/email     "a@example.com"
                                      :user/joined-at now}]})))))
