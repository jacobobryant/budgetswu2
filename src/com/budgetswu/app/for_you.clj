(ns com.budgetswu.app.for-you
  (:require [com.biffweb.fx :refer [defpipeline]]
            [com.budgetswu.lib.middleware :as mid]
            [com.budgetswu.lib.ranking :as ranking]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]))

(defpipeline default-page
  [:biff.graph.fx/query
   [{:session/user [{:user/owned-cards [:owned-card/id]}]}]]

  (fn [_ {:keys [session/user]}]
    {:status  303
     :headers {"location" (if (seq (:user/owned-cards user))
                            (routes/for-you)
                            (routes/cards))}}))

(defpipeline for-you-page
  [:biff.graph.fx/query
   [{:session/user [{:user/recommendations ranking/fields}
                    {:user/owned-cards [:owned-card/id]}]}]]

  (fn [request {:keys [session/user]}]
    (let [{:user/keys [recommendations owned-cards]} user]
      (ui/app-page
       request
       (ui/shell
        {:current (routes/for-you)}
        [:p {:class ["mb-6 max-w-2xl text-stone-300"]}
         (if (seq owned-cards)
           "Prices below only count the cards you don't already own."
           [:span
            "Prices below assume you own no cards yet. "
            [:a {:href (routes/cards) :class ui/link-class}
             "Add the cards you own"]
            " to get updated recommendations."])]
        (recommendations/recommendation-list recommendations
                                             {:personal true}))))))

(def module
  {:biff.ring/routes
   ["" {:middleware [mid/wrap-signed-in]}
    [(routes/app)     {:get default-page}]
    [(routes/for-you) {:get for-you-page}]]})
