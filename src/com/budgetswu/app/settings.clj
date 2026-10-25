(ns com.budgetswu.app.settings
  (:require [com.biffweb.fx :refer [defpipeline]]
            [com.biffweb.ring :refer [defpath]]
            [com.budgetswu.lib.middleware :as mid]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]))

(defpath set-notifications-path "/settings/notifications")

;;;; Page ======================================================================

(defn- toggle-button [enabled label]
  [:button {:type          "button"
            :data-action   (set-notifications-path {:enabled enabled})
            :data-on:click "@post(el.dataset.action)"
            :class         ["shrink-0 rounded-md px-4 py-2 text-sm font-semibold"
                            "bg-orange-700 text-white hover:bg-orange-800"]}
   label])

(defpipeline settings-page
  [:biff.graph.fx/query
   [{:session/user [:user/email :?user/unsubscribed-at]}]]

  (fn [request {:keys [session/user]}]
    (let [subscribed (nil? (:user/unsubscribed-at user))]
      (ui/app-page
       request
       (ui/shell
        request
        {:current (routes/settings)}
        [:section {:class ["rounded-lg bg-white p-4 text-stone-900 shadow-lg sm:p-6"]}
         [:h2 {:class ["text-lg font-semibold"]} "Email notifications"]
         [:div {:class ["mt-2 flex flex-wrap items-center justify-between gap-4"]}
          [:p {:class ["max-w-xl text-stone-600"]}
           (if subscribed
             (str "We email " (:user/email user) " when there are new deck "
                  "recommendations for you, at most once a day.")
             (str "You're unsubscribed. We won't email " (:user/email user)
                  " about new deck recommendations."))]
          (if subscribed
            (toggle-button false "Unsubscribe")
            (toggle-button true "Resubscribe"))]])))))

;;;; Actions ===================================================================

(defpipeline set-notifications
  [:biff.graph.fx/query [{:session/user [:user/id]} :request/email-notifications]]

  (fn [{:biff.fx/keys [now]} {:keys [session/user request/email-notifications]}]
    {:_      [:biff.sqlite.fx/authorized-write
              {:update :user
               :set    {:user/unsubscribed-at (when-not email-notifications now)}
               :where  [:= :user/id (:user/id user)]}]
     :status 204}))

(def module
  {:biff.ring/routes
   ["" {:middleware [mid/wrap-signed-in]}
    [(routes/settings)         {:get settings-page}]
    [(set-notifications-path)  {:post set-notifications}]]})
