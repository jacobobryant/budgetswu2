(ns com.budgetswu.model.global
  (:require [com.biffweb.graph :refer [defresolver]]
            [com.budgetswu.lib.pareto :as pareto]
            [com.budgetswu.lib.recommendations :as recommendations]
            [tick.core :as tick]))

(defresolver sync-states
  {:output [{:global/sync-states
             [:sync-state/id [:? :sync-state/watermark]
              [:? :sync-state/attempted-at]]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select [:sync-state/id :sync-state/watermark
               :sync-state/attempted-at]
      :from   :sync-state}])

  (fn [_ rows]
    {:global/sync-states rows}))

(defresolver metas
  {:output [{:global/metas [:meta/id :meta/is-current]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select [:meta/id :meta/is-current] :from :meta}])

  (fn [_ rows]
    {:global/metas rows}))

(defresolver tournaments
  {:output [{:global/tournaments
             [:tournament/id :tournament/held-at]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select [:tournament/id :tournament/held-at]
      :from   :tournament}])

  (fn [_ rows]
    {:global/tournaments rows}))

(defresolver card-sets
  {:output [{:global/card-sets [:card-set/id
                                [:? :card-set/number]
                                [:? :card-set/published-on]]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select [:card-set/id :card-set/number :card-set/published-on]
      :from   :card-set}])

  (fn [_ rows]
    {:global/card-sets rows}))

(defresolver standard-printings
  {:output [{:global/standard-printings
             [:printing/card-id :printing/collector-number]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select-distinct [:printing/card-id :printing/collector-number]
      :from            :printing
      :where           [:in :printing/variant ["Standard" "Hyperspace"]]}])

  (fn [_ rows]
    {:global/standard-printings rows}))

(defresolver current-recommendations
  {:output [{:global/current-recommendations [:recommendation/id]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select [:decklist/id]
      :from   :decklist
      :join   [:tournament [:= :decklist/tournament-id :tournament/id]
               :meta [:= :meta/is-current true]]
      :where  [:and
               [:>= :tournament/held-at :meta/start-date]
               [:or
                [:= :meta/end-date nil]
                [:< :tournament/held-at :meta/end-date]]]}])

  (fn [_ rows]
    {:global/current-recommendations
     (mapv (fn [row] {:recommendation/id (:decklist/id row)}) rows)}))

(defresolver front
  {:input  [{:global/current-recommendations recommendations/candidate-fields}]
   :output [{:global/front [:recommendation/id]}]}
  [_ {:global/keys [current-recommendations]}]
  {:global/front
   (mapv #(select-keys % [:recommendation/id])
         (pareto/front (filterv :recommendation/eligible current-recommendations)
                       recommendations/objectives))})

(defresolver ranked-recommendations
  {:output [{:global/ranked-recommendations [:recommendation/id]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select   [:recommendation/id]
      :from     :recommendation
      :order-by [:recommendation/rank]}])

  (fn [_ rows]
    {:global/ranked-recommendations rows}))

;; Ranks are saved by the sync after its decklists, so a failed sync can leave
;; ranks for decks that are no longer eligible.
(defresolver stored-recommendations
  {:input  [{:global/ranked-recommendations [:recommendation/id [:? :recommendation/eligible]]}]
   :output [{:global/recommendations [:recommendation/id]}]}
  [_ {:global/keys [ranked-recommendations]}]
  {:global/recommendations
   (into []
         (comp (filter :recommendation/eligible)
               (map #(select-keys % [:recommendation/id])))
         ranked-recommendations)})

(defresolver users
  {:output [{:global/users [:user/id :user/email :user/joined-at]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select   [:user/id :user/email :user/joined-at]
      :from     :user
      :order-by [[:user/joined-at :desc]]}])

  (fn [_ rows]
    {:global/users rows}))

(def notification-interval (tick/new-duration 24 :hours))

(defresolver notification-candidates
  {:output [{:global/notification-candidates [:user/id]}]}

  (fn [{:biff.fx/keys [now]} _]
    [:biff.sqlite.fx/execute
     {:select [:user/id]
      :from   :user
      :where  [:and
               [:= :user/unsubscribed-at nil]
               [:not [:exists
                      {:select [1]
                       :from   :notification
                       :where  [:and
                                [:= :notification/user-id :user/id]
                                [:> :notification/created-at
                                 (tick/<< now notification-interval)]]}]]]}])

  (fn [_ rows]
    {:global/notification-candidates rows}))

(def module
  {:biff.graph/resolvers
   [sync-states
    metas
    tournaments
    card-sets
    standard-printings
    current-recommendations
    front
    ranked-recommendations
    stored-recommendations
    users
    notification-candidates]})
