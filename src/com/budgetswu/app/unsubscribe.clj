(ns com.budgetswu.app.unsubscribe
  (:require [com.biffweb.fx :refer [defpipeline]]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]))

;;;; Page ======================================================================

(defn- panel [& content]
  (into [:section {:class ["rounded-lg bg-white p-4 text-stone-900 shadow-lg sm:p-6"]}]
        content))

(defpipeline unsubscribe-page
  [:biff.graph.fx/query
   [{:?request/notification
     [:notification/token
      {:notification/user [:user/email :?user/unsubscribed-at]}]}]]

  (fn [request {:keys [request/notification]}]
    (let [{:user/keys [email unsubscribed-at]} (:notification/user notification)]
      (ui/app-page
       request
       (ui/shell
        request
        {}
        (cond
          (nil? notification)
          (panel
           [:h2 {:class ["text-lg font-semibold"]} "This link has expired"]
           [:p {:class ["mt-2 text-stone-600"]}
            "Unsubscribe links work for 30 days. "
            [:a {:href  (routes/settings)
                 :class ["font-semibold text-orange-700 underline-offset-4"
                         "hover:underline"]}
             "Sign in to manage your email settings"]
            " instead."])

          (some? unsubscribed-at)
          (panel
           [:h2 {:class ["text-lg font-semibold"]} "You're unsubscribed"]
           [:p {:class ["mt-2 text-stone-600"]}
            (str "We won't email " email " about new deck recommendations.")])

          :else
          (panel
           [:h2 {:class ["text-lg font-semibold"]} "Unsubscribe from BudgetSWU emails?"]
           [:p {:class ["mt-2 text-stone-600"]}
            (str "We'll stop emailing " email " about new deck recommendations.")]
           [:button {:type          "button"
                     :data-action   (routes/unsubscribe (:notification/token notification))
                     :data-on:click "@post(el.dataset.action)"
                     :class         ["mt-4 rounded-md bg-orange-700 px-4 py-2 text-sm"
                                     "font-semibold text-white hover:bg-orange-800"]}
            "Unsubscribe"])))))))

;;;; Actions ===================================================================

;; The token in the link stands in for signing in, so this write can't go
;; through the session-based authorization rules.
(defpipeline unsubscribe
  [:biff.graph.fx/query [{:request/notification [{:notification/user [:user/id]}]}]]

  (fn [{:biff.fx/keys [now]} {:keys [request/notification]}]
    {:_      [:biff.sqlite.fx/execute
              {:update :user
               :set    {:user/unsubscribed-at now}
               :where  [:and
                        [:= :user/id (get-in notification [:notification/user :user/id])]
                        [:= :user/unsubscribed-at nil]]}]
     :status 204}))

(def module
  {:biff.ring/routes
   ["" {}
    [(routes/unsubscribe) {:get  unsubscribe-page
                           :post unsubscribe}]]})
