(ns com.budgetswu.app.decks
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
                            (routes/decks)
                            (routes/cards))}}))

(defpipeline decks-page
  [:biff.graph.fx/query
   [{:session/user [:user/id
                    [:? :user/seen-recommendations]
                    {:user/recommendations ranking/fields}
                    {:user/owned-cards [:owned-card/id]}]}]]

  (fn [request {:keys [session/user]}]
    (let [{:user/keys [id recommendations owned-cards seen-recommendations]} user

          current (into #{} (map :recommendation/id) recommendations)]
      (cond-> (ui/app-page
               request
               (ui/shell
                request
                {:current (routes/decks)}
                [:p {:class ["mb-6 max-w-2xl text-stone-300"]}
                 (if (seq owned-cards)
                   "Prices below only count the cards you don't already own."
                   [:span
                    "Prices below assume you own no cards yet. "
                    [:a {:href (routes/cards) :class ui/link-class}
                     "Add the cards you own"]
                    " to get updated recommendations."])]
                (recommendations/recommendation-list recommendations
                                                     {:personal true})))
        ;; Viewing the page marks the current recommendations as seen, so
        ;; notification emails only mention decks added after this.
        (not= current seen-recommendations)
        (assoc :_ [:biff.sqlite.fx/authorized-write
                   {:update :user
                    :set    {:user/seen-recommendations [:lift current]}
                    :where  [:= :user/id id]}])))))

(def module
  {:biff.ring/routes
   ["" {:middleware [mid/wrap-signed-in]}
    [(routes/app)   {:get default-page}]
    [(routes/decks) {:get decks-page}]]})
