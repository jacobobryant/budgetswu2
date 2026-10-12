(ns com.budgetswu.model.global
  (:require [com.biffweb.graph :refer [defresolver]]
            [com.budgetswu.lib.ranking :as ranking]
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

(defresolver front
  {:output [{:global/front ranking/fields}]}

  (fn [_ _]
    {:decks      [:biff.sqlite.fx/execute ranking/current-deck-query]
     :cards      [:biff.sqlite.fx/execute ranking/card-query]
     :archetypes [:biff.sqlite.fx/execute ranking/current-archetype-query]})

  (fn [_ {:keys [decks cards archetypes]}]
    {:global/front (ranking/pareto-front (ranking/candidates decks cards archetypes {}))}))

(defresolver stored
  {:output [{:global/recommendations ranking/fields}]}

  (fn [_ _]
    {:decks      [:biff.sqlite.fx/execute ranking/deck-query]
     :cards      [:biff.sqlite.fx/execute ranking/card-query]
     :archetypes [:biff.sqlite.fx/execute ranking/current-archetype-query]
     :ranks      [:biff.sqlite.fx/execute
                  {:select [:recommendation/id :recommendation/rank]
                   :from   :recommendation}]})

  (fn [_ {:keys [decks cards archetypes ranks]}]
    (let [rank-by-id (into {}
                           (map (juxt :recommendation/id
                                      :recommendation/rank))
                           ranks)]
      {:global/recommendations
       (->> (ranking/candidates decks cards archetypes {})
            (filterv #(contains? rank-by-id (:recommendation/id %)))
            (sort-by #(get rank-by-id (:recommendation/id %)))
            vec)})))

(defresolver home-decklists
  {:output [{:global/home-decklists [:recommendation/id]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select [:recommendation/id] :from :recommendation}])

  (fn [_ rows]
    {:global/home-decklists rows}))

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
    front
    stored
    home-decklists
    notification-candidates]})
