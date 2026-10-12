(ns com.budgetswu.model.global
  (:require [com.biffweb.graph :refer [defresolver]]))

(defn placement-percentile [placement field-size]
  (when (and (some? placement)
             (some? field-size)
             (pos? field-size)
             (<= 1 placement field-size))
    (/ (- field-size placement) field-size)))

(defn deck-price [cards]
  (when (and (seq cards)
             (every? #(and (some? (:deck-card/quantity %))
                           (some? (:card-price/cents %)))
                     cards))
    (reduce + (mapv #(* (:deck-card/quantity %)
                        (:card-price/cents %))
                    cards))))

(defn archetype-popularity
  "Returns a map from archetype to its frequency, competition ranking (#1 is
  most popular), and the number of distinct archetypes."
  [archetypes]
  (let [freqs     (frequencies archetypes)
        counts    (vec (sort > (vals freqs)))
        rank-by-n (reduce-kv (fn [acc i n]
                               (cond-> acc
                                 (not (contains? acc n)) (assoc n (inc i))))
                             {}
                             counts)]
    (update-vals freqs
                 (fn [n]
                   {:recommendation/archetype-frequency n
                    :recommendation/popularity-rank     (rank-by-n n)
                    :recommendation/archetype-count     (count freqs)}))))

(defn deck-archetypes
  "Returns a map from decklist ID to archetype: the leader and base aspects.
  Decklists without a known leader have no archetype."
  [rows]
  (update-vals (group-by :decklist/id (filterv :decklist/leader-name rows))
               (fn [deck-rows]
                 [(:decklist/leader-name (first deck-rows))
                  (into (sorted-set) (keep :card-aspect/aspect) deck-rows)])))

(defn candidate [deck cards popularity]
  (let [price      (deck-price cards)
        percentile (placement-percentile
                    (:standing/final-placement deck)
                    (:tournament/player-count deck))]
    (when (and (some? price)
               (some? percentile)
               (some? popularity)
               (<= 50 (reduce + (mapv :deck-card/quantity cards))))
      (merge
       popularity
       {:recommendation/id              (:decklist/id deck)
        :recommendation/melee-id        (:decklist/melee-id deck)
        :recommendation/leader-name     (:decklist/leader-name deck)
        :recommendation/base-name       (:decklist/base-name deck)
        :recommendation/player-name     (:decklist/player-name deck)
        :recommendation/tournament-name (:tournament/name deck)
        :recommendation/tournament-date (:tournament/held-at deck)
        :recommendation/player-count    (:tournament/player-count deck)
        :recommendation/placement       (:standing/final-placement deck)
        :recommendation/price-cents     price
        :recommendation/percentile      percentile}))))

(defn dominates? [a b]
  (let [a-price     (:recommendation/price-cents a)
        b-price     (:recommendation/price-cents b)
        a-placement (:recommendation/percentile a)
        b-placement (:recommendation/percentile b)
        a-frequency (:recommendation/archetype-frequency a)
        b-frequency (:recommendation/archetype-frequency b)]
    (and (<= a-price b-price)
         (>= a-placement b-placement)
         (<= a-frequency b-frequency)
         (or (< a-price b-price)
             (> a-placement b-placement)
             (< a-frequency b-frequency)))))

(defn pareto-front [candidates]
  (->> candidates
       (filterv (fn [item]
                  (not-any? #(dominates? % item) candidates)))
       (sort-by (juxt :recommendation/price-cents
                      :recommendation/archetype-frequency
                      :recommendation/id))
       vec))

(def deck-query
  {:select [:decklist/id :decklist/melee-id :decklist/leader-name
            :decklist/base-name :decklist/player-name :tournament/name
            :tournament/held-at :tournament/player-count
            :standing/final-placement]
   :from   :decklist
   :join   [:tournament [:= :decklist/tournament-id :tournament/id]
            :standing [:= :standing/decklist-id :decklist/id]]})

(def current-deck-query
  (-> deck-query
      (update :join into [:meta [:= :meta/is-current true]])
      (assoc :where
             [:and
              [:>= :tournament/held-at :meta/start-date]
              [:or [:= :meta/end-date nil]
               [:< :tournament/held-at :meta/end-date]]])))

(def current-archetype-query
  {:select    [:decklist/id :decklist/leader-name :card-aspect/aspect]
   :from      :decklist
   :join      [:tournament [:= :decklist/tournament-id :tournament/id]
               :meta [:= :meta/is-current true]]
   :left-join [:card [:= :card/collector-number :decklist/base-number]
               :card-aspect [:= :card-aspect/card-id :card/id]]
   :where     (:where current-deck-query)})

(def card-query
  {:select    [:deck-card/decklist-id :deck-card/quantity
               :card-price/cents]
   :from      :deck-card
   :left-join [:card-price
               [:= :deck-card/collector-number :card-price/id]]})

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

(defresolver decklists
  {:output [{:global/decklists
             [:decklist/id :decklist/tournament-id]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select [:decklist/id :decklist/tournament-id]
      :from   :decklist}])

  (fn [_ rows]
    {:global/decklists rows}))

(defresolver leaders-and-bases
  {:output [{:global/leaders-and-bases
             [:card/collector-number :card/type]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select-distinct [:card/collector-number :card/type]
      :from            :card
      :where           [:in :card/type ["Leader" "Base"]]}])

  (fn [_ rows]
    {:global/leaders-and-bases rows}))

(defresolver card-sets
  {:output [{:global/card-sets [:card/set-code]}]}

  (fn [_ _]
    [:biff.sqlite.fx/execute
     {:select-distinct [:card/set-code] :from :card}])

  (fn [_ rows]
    {:global/card-sets rows}))

(def fields
  [:recommendation/id
   [:? :recommendation/melee-id]
   [:? :recommendation/leader-name]
   [:? :recommendation/base-name]
   [:? :recommendation/player-name]
   :recommendation/tournament-name
   :recommendation/tournament-date
   :recommendation/player-count
   :recommendation/placement
   :recommendation/price-cents
   :recommendation/percentile
   :recommendation/archetype-frequency
   :recommendation/popularity-rank
   :recommendation/archetype-count])

(defn- candidate-rows [decks cards archetype-rows]
  (let [cards-by-deck (group-by :deck-card/decklist-id cards)
        archetypes    (deck-archetypes archetype-rows)
        popularity    (archetype-popularity (vals archetypes))]
    (into []
          (keep (fn [{:decklist/keys [id] :as deck}]
                  (candidate deck
                             (get cards-by-deck id)
                             (get popularity (get archetypes id)))))
          decks)))

(defresolver front
  {:output [{:global/front fields}]}

  (fn [_ _]
    {:decks      [:biff.sqlite.fx/execute current-deck-query]
     :cards      [:biff.sqlite.fx/execute card-query]
     :archetypes [:biff.sqlite.fx/execute current-archetype-query]})

  (fn [_ {:keys [decks cards archetypes]}]
    {:global/front (pareto-front (candidate-rows decks cards archetypes))}))

(defresolver stored
  {:output [{:global/recommendations fields}]}

  (fn [_ _]
    {:decks      [:biff.sqlite.fx/execute deck-query]
     :cards      [:biff.sqlite.fx/execute card-query]
     :archetypes [:biff.sqlite.fx/execute current-archetype-query]
     :ranks      [:biff.sqlite.fx/execute
                  {:select [:recommendation/id :recommendation/rank]
                   :from   :recommendation}]})

  (fn [_ {:keys [decks cards archetypes ranks]}]
    (let [rank-by-id (into {}
                           (map (juxt :recommendation/id
                                      :recommendation/rank))
                           ranks)]
      {:global/recommendations
       (->> (candidate-rows decks cards archetypes)
            (filterv #(contains? rank-by-id (:recommendation/id %)))
            (sort-by #(get rank-by-id (:recommendation/id %)))
            vec)})))

(def module
  {:biff.graph/resolvers
   [sync-states
    metas
    tournaments
    decklists
    leaders-and-bases
    card-sets
    front
    stored]})
