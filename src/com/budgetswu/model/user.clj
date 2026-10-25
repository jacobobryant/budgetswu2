(ns com.budgetswu.model.user
  (:require [com.biffweb.graph :refer [defresolver]]
            [com.budgetswu.lib.pareto :as pareto]
            [com.budgetswu.lib.recommendations :as recommendations]))

(defresolver recommendation-candidates
  {:input  [:user/id {:global/current-recommendations [:recommendation/id]}]
   :output [{:user/recommendation-candidates
             [:recommendation/id {:recommendation/user [:user/id]}]}]
   :batch  true}
  [_ inputs]
  (mapv (fn [{:user/keys [id] :global/keys [current-recommendations]}]
          {:user/recommendation-candidates
           (mapv #(assoc % :recommendation/user {:user/id id}) current-recommendations)})
        inputs))

(defresolver recommendations
  {:input  [{:user/recommendation-candidates
             [:recommendation/id
              {:recommendation/user [:user/id]}
              :?recommendation/eligible
              :?recommendation/price-cents
              :?recommendation/percentile
              :?recommendation/archetype-frequency]}]
   :output [{:user/recommendations
             [:recommendation/id {:recommendation/user [:user/id]}]}]
   :batch  true}
  [_ inputs]
  (mapv (fn [{:user/keys [recommendation-candidates]}]
          {:user/recommendations
           (mapv #(select-keys % [:recommendation/id :recommendation/user])
                 (pareto/front (filterv :recommendation/eligible recommendation-candidates)
                               recommendations/objectives))})
        inputs))

(def module
  {:biff.graph/resolvers [recommendation-candidates recommendations]})
