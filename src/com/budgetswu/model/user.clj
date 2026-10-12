(ns com.budgetswu.model.user
  (:require [com.biffweb.graph :refer [defresolver]]
            [com.budgetswu.lib.ranking :as ranking]))

(defresolver session-user
  {:output [{:session/user [:user/id]}]}
  [{:keys [session]} _]
  (when-some [uid (:uid session)]
    {:session/user {:user/id uid}}))

(defresolver owned-cards
  {:input  [:user/id]
   :output [{:user/owned-cards
             [:owned-card/id :owned-card/card-id :owned-card/quantity]}]
   :batch  true}

  (fn [_ inputs]
    {:inputs inputs
     :rows   [:biff.sqlite.fx/execute
              {:select [:owned-card/id
                        :owned-card/user-id
                        :owned-card/card-id
                        :owned-card/quantity]
               :from   :owned-card
               :where  [:and
                        [:in :owned-card/user-id (mapv :user/id inputs)]
                        [:> :owned-card/quantity 0]]}]})

  (fn [_ {:keys [inputs rows]}]
    (let [by-user (group-by :owned-card/user-id rows)]
      (mapv (fn [{:user/keys [id]}]
              {:user/owned-cards
               (mapv #(dissoc % :owned-card/user-id) (get by-user id []))})
            inputs))))

(defresolver recommendations
  {:input  [{:user/owned-cards [:owned-card/card-id :owned-card/quantity]}]
   :output [{:user/recommendations ranking/fields}]}

  (fn [_ {:user/keys [owned-cards]}]
    {:owned      (into {}
                       (map (juxt :owned-card/card-id :owned-card/quantity))
                       owned-cards)
     :decks      [:biff.sqlite.fx/execute ranking/current-deck-query]
     :cards      [:biff.sqlite.fx/execute ranking/card-query]
     :archetypes [:biff.sqlite.fx/execute ranking/current-archetype-query]})

  (fn [_ {:keys [owned decks cards archetypes]}]
    {:user/recommendations
     (ranking/pareto-front (ranking/candidates decks cards archetypes owned))}))

(def module
  {:biff.graph/resolvers [session-user owned-cards recommendations]})
