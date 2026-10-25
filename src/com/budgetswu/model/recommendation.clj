(ns com.budgetswu.model.recommendation
  "A recommendation is a decklist as recommended to a user, or to everyone when
  :recommendation/user is absent. Recommendation IDs are decklist IDs."
  (:require [com.biffweb.graph :refer [defresolver]]))

(def min-deck-size 50)

(defn placement-percentile [placement field-size]
  (when (and (some? placement)
             (some? field-size)
             (pos? field-size)
             (<= 1 placement field-size))
    (/ (- field-size placement) field-size)))

(defn deck-prices
  "Returns the full price of a deck and the price of only the cards the user
  doesn't already own, or nil if any card is unpriced. `owned` maps card IDs to
  owned quantities."
  [cards owned]
  (when (and (seq cards)
             (every? #(and (some? (:deck-card/quantity %))
                           (some? (:card/price-cents %)))
                     cards))
    (let [needed (update-vals (group-by :deck-card/card-id cards)
                              (fn [rows]
                                {:quantity (reduce + (mapv :deck-card/quantity rows))
                                 :cents    (:card/price-cents (first rows))}))]
      (reduce-kv (fn [acc card-id {:keys [quantity cents]}]
                   (let [missing (max 0 (- quantity (get owned card-id 0)))]
                     (-> acc
                         (update :recommendation/full-price-cents + (* quantity cents))
                         (update :recommendation/price-cents + (* missing cents)))))
                 {:recommendation/full-price-cents 0 :recommendation/price-cents 0}
                 needed))))

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
  (update-vals (group-by :decklist/id (filterv :decklist/leader-id rows))
               (fn [deck-rows]
                 [(:decklist/leader-id (first deck-rows))
                  (into (sorted-set) (keep :card-aspect/aspect) deck-rows)])))

(defresolver details
  {:input  [:recommendation/id]
   :output [:recommendation/melee-id
            :recommendation/leader-name
            :recommendation/base-name
            :recommendation/player-name
            :recommendation/tournament-name
            :recommendation/tournament-date
            :recommendation/player-count
            :recommendation/placement
            :recommendation/percentile]
   :batch  true}

  (fn [_ inputs]
    {:inputs inputs
     :rows   [:biff.sqlite.fx/execute
              {:select [:decklist/id
                        :decklist/leader-id
                        :decklist/base-id
                        :decklist/player-name
                        :decklist/placement
                        :tournament/name
                        :tournament/held-at
                        :tournament/player-count]
               :from   :decklist
               :join   [:tournament [:= :decklist/tournament-id :tournament/id]]
               :where  [:in :decklist/id (mapv :recommendation/id inputs)]}]})

  (fn [_ {:keys [inputs rows]}]
    (let [by-id (into {} (map (juxt :decklist/id identity)) rows)]
      (mapv (fn [{:recommendation/keys [id]}]
              (if-some [deck (get by-id id)]
                {:recommendation/melee-id        (:decklist/id deck)
                 :recommendation/leader-name     (:decklist/leader-id deck)
                 :recommendation/base-name       (:decklist/base-id deck)
                 :recommendation/player-name     (:decklist/player-name deck)
                 :recommendation/tournament-name (:tournament/name deck)
                 :recommendation/tournament-date (:tournament/held-at deck)
                 :recommendation/player-count    (:tournament/player-count deck)
                 :recommendation/placement       (:decklist/placement deck)
                 :recommendation/percentile      (placement-percentile
                                                  (:decklist/placement deck)
                                                  (:tournament/player-count deck))}
                {}))
            inputs))))

(defresolver popularity
  {:input  [:recommendation/id]
   :output [:recommendation/archetype-frequency
            :recommendation/popularity-rank
            :recommendation/archetype-count]
   :batch  true}

  (fn [_ inputs]
    {:inputs inputs
     :rows   [:biff.sqlite.fx/execute
              {:select    [:decklist/id :decklist/leader-id :card-aspect/aspect]
               :from      :decklist
               :join      [:tournament [:= :decklist/tournament-id :tournament/id]
                           :meta [:= :meta/is-current true]]
               :left-join [:card-aspect [:= :card-aspect/card-id :decklist/base-id]]
               :where     [:and
                           [:>= :tournament/held-at :meta/start-date]
                           [:or
                            [:= :meta/end-date nil]
                            [:< :tournament/held-at :meta/end-date]]]}]})

  (fn [_ {:keys [inputs rows]}]
    (let [archetypes (deck-archetypes rows)
          popularity (archetype-popularity (vals archetypes))]
      (mapv #(get popularity (get archetypes (:recommendation/id %)) {}) inputs))))

(defresolver prices
  {:input  [:recommendation/id
            {:?recommendation/user
             [{:user/owned-cards [:owned-card/card-id :owned-card/quantity]}]}]
   :output [:recommendation/card-count
            :recommendation/price-cents
            :recommendation/full-price-cents]
   :batch  true}

  (fn [_ inputs]
    {:inputs inputs
     :rows   [:biff.sqlite.fx/execute
              {:select    [:deck-card/decklist-id
                           :deck-card/card-id
                           :deck-card/quantity
                           :card/price-cents]
               :from      :deck-card
               :left-join [:card [:= :deck-card/card-id :card/id]]
               :where     [:in :deck-card/decklist-id (mapv :recommendation/id inputs)]}]})

  (fn [_ {:keys [inputs rows]}]
    (let [by-deck (group-by :deck-card/decklist-id rows)]
      (mapv (fn [{:recommendation/keys [id user]}]
              (let [cards (get by-deck id)
                    owned (into {}
                                (map (juxt :owned-card/card-id :owned-card/quantity))
                                (:user/owned-cards user))]
                (merge {:recommendation/card-count (transduce (keep :deck-card/quantity) + 0 cards)}
                       (deck-prices cards owned))))
            inputs))))

(defresolver eligible
  {:input  [:recommendation/card-count
            :recommendation/price-cents
            :recommendation/percentile
            :recommendation/archetype-frequency]
   :output [:recommendation/eligible]}
  [_ {:recommendation/keys [card-count]}]
  {:recommendation/eligible (<= min-deck-size card-count)})

(def module
  {:biff.graph/resolvers [details popularity prices eligible]})
