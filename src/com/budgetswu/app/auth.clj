(ns com.budgetswu.app.auth
  (:require [com.biffweb.authenticate :as biff.auth]
            [com.biffweb.fx :as fx :refer [defpipeline]]
            [com.budgetswu.lib.email :as email]
            [com.budgetswu.routes :as routes]))

(defpipeline get-user-id
  (fn [_ctx email]
    [:biff.sqlite.fx/execute
     {:select [:user/id]
      :from   :user
      :where  [:= :user/email email]}])

  (fn [_ctx result]
    (-> result first :user/id)))

(defpipeline create-user
  (fn [_ctx {:keys [email]}]
    {:email email
     :home  [:biff.graph.fx/query [{:global/home-decklists [:recommendation/id]}]]})

  ;; New users have already seen the home page's recommendations, so only
  ;; decks added after sign-up count as new for notification emails.
  (fn [{:biff.fx/keys [now random-uuid7-seq]} {:keys [email home]}]
    (let [[new-user-id] random-uuid7-seq
          seen          (into #{}
                              (map :recommendation/id)
                              (:global/home-decklists home))]
      [:biff.sqlite.fx/execute
       {:insert-into   :user
        :values        [{:user/id                   new-user-id
                         :user/email                email
                         :user/joined-at            now
                         :user/seen-recommendations [:lift seen]}]
        :on-conflict   [:user/email]
        :do-update-set [:user/email]
        :returning     [:user/id]}]))

  (fn [_ctx result]
    (-> result first :user/id)))

(def module
  (biff.auth/module
   (merge
    {:biff.auth/app-path             (routes/app)
     :biff.auth/app-name             "BudgetSWU"
     :biff.auth/primary-color        "#c2410c"
     :biff.auth/send-email           #'email/send-email
     :biff.auth/get-user-id          #'get-user-id
     :biff.auth/create-user          #'create-user
     ;; We're using com.biffweb.ring/wrap-csrf-protection.
     :biff.auth/skip-csrf-protection true}
    biff.auth/turnstile-config)))
