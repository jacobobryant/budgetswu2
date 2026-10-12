(ns com.budgetswu.lib.ranking
  "Pure functions and queries for ranking decklists. Shared by the global and
  per-user recommendation resolvers.")

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
                         (update :full + (* quantity cents))
                         (update :remaining + (* missing cents)))))
                 {:full 0 :remaining 0}
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

(defn candidate [deck cards popularity owned]
  (let [prices     (deck-prices cards owned)
        percentile (placement-percentile
                    (:decklist/placement deck)
                    (:tournament/player-count deck))]
    (when (and (some? prices)
               (some? percentile)
               (some? popularity)
               (<= 50 (reduce + (mapv :deck-card/quantity cards))))
      (merge
       popularity
       {:recommendation/id               (:decklist/id deck)
        :recommendation/melee-id         (:decklist/id deck)
        :recommendation/leader-name      (:decklist/leader-id deck)
        :recommendation/base-name        (:decklist/base-id deck)
        :recommendation/player-name      (:decklist/player-name deck)
        :recommendation/tournament-name  (:tournament/name deck)
        :recommendation/tournament-date  (:tournament/held-at deck)
        :recommendation/player-count     (:tournament/player-count deck)
        :recommendation/placement        (:decklist/placement deck)
        :recommendation/price-cents      (:remaining prices)
        :recommendation/full-price-cents (:full prices)
        :recommendation/percentile       percentile}))))

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

(defn candidates
  "Returns recommendation candidates for decklists, pricing each deck by the
  cards missing from `owned`."
  [decks cards archetype-rows owned]
  (let [cards-by-deck (group-by :deck-card/decklist-id cards)
        archetypes    (deck-archetypes archetype-rows)
        popularity    (archetype-popularity (vals archetypes))]
    (into []
          (keep (fn [{:decklist/keys [id] :as deck}]
                  (candidate deck
                             (get cards-by-deck id)
                             (get popularity (get archetypes id))
                             owned)))
          decks)))

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
   :recommendation/full-price-cents
   :recommendation/percentile
   :recommendation/archetype-frequency
   :recommendation/popularity-rank
   :recommendation/archetype-count])

(def deck-query
  {:select [:decklist/id
            :decklist/leader-id
            :decklist/base-id
            :decklist/player-name
            :decklist/placement
            :tournament/name
            :tournament/held-at
            :tournament/player-count]
   :from   :decklist
   :join   [:tournament [:= :decklist/tournament-id :tournament/id]]})

(def current-meta-where
  [:and
   [:>= :tournament/held-at :meta/start-date]
   [:or [:= :meta/end-date nil]
    [:< :tournament/held-at :meta/end-date]]])

(def current-deck-query
  (-> deck-query
      (update :join into [:meta [:= :meta/is-current true]])
      (assoc :where current-meta-where)))

(def current-archetype-query
  {:select    [:decklist/id :decklist/leader-id :card-aspect/aspect]
   :from      :decklist
   :join      [:tournament [:= :decklist/tournament-id :tournament/id]
               :meta [:= :meta/is-current true]]
   :left-join [:card-aspect [:= :card-aspect/card-id :decklist/base-id]]
   :where     current-meta-where})

(def card-query
  {:select    [:deck-card/decklist-id
               :deck-card/card-id
               :deck-card/quantity
               :card/price-cents]
   :from      :deck-card
   :left-join [:card [:= :deck-card/card-id :card/id]]})
