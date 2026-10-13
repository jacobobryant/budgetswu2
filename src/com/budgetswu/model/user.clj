(ns com.budgetswu.model.user
  (:require [com.biffweb.graph :refer [defresolver]]
            [com.budgetswu.lib.pareto :as pareto]
            [com.budgetswu.lib.recommendations :as recommendations]))

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

(defresolver recommendation-candidates
  {:input  [:user/id]
   :output [{:user/recommendation-candidates
             [:recommendation/id {:recommendation/user [:user/id]}]}]}

  (fn [_ {:user/keys [id]}]
    {:user-id id
     :global  [:biff.graph.fx/query
               [{:global/current-recommendations [:recommendation/id]}]]})

  (fn [_ {:keys [user-id global]}]
    {:user/recommendation-candidates
     (mapv #(assoc % :recommendation/user {:user/id user-id})
           (:global/current-recommendations global))}))

(defresolver recommendations
  {:input  [{:user/recommendation-candidates
             (conj recommendations/candidate-fields {:recommendation/user [:user/id]})}]
   :output [{:user/recommendations
             [:recommendation/id {:recommendation/user [:user/id]}]}]}
  [_ {:user/keys [recommendation-candidates]}]
  {:user/recommendations
   (mapv #(select-keys % [:recommendation/id :recommendation/user])
         (pareto/front (filterv :recommendation/eligible recommendation-candidates)
                       recommendations/objectives))})

(def module
  {:biff.graph/resolvers [owned-cards recommendation-candidates recommendations]})
