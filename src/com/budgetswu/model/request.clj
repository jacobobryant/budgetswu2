(ns com.budgetswu.model.request
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [com.biffweb.graph :refer [defresolver]]
            [com.budgetswu.schema :as schema]
            [tick.core :as tick]))

(def page-size 50)

(defn earliest-legal-set
  "Returns the number of the earliest Premier-legal set, given the number of
  the latest released set. Sets rotate out in yearly blocks of three: when the
  first set of a year is released, the sets from two years prior rotate out."
  [latest]
  (max 1 (- (* 3 (quot (dec latest) 3)) 2)))

(defn legal-printing-where
  "Returns a HoneySQL condition matching printings in legal sets: numbered sets
  at or after the earliest legal set, and unnumbered (promo) sets published on
  or after it."
  [earliest-number earliest-published-on]
  [:or
   [:>= :card-set/number earliest-number]
   (if earliest-published-on
     [:and
      [:= :card-set/number nil]
      [:>= :card-set/published-on earliest-published-on]]
     false)])

(defn legal-cards-query [card-sets latest-set-id]
  (let [by-id     (into {} (map (juxt :card-set/id identity)) card-sets)
        latest    (get-in by-id [latest-set-id :card-set/number])
        earliest  (some-> latest earliest-legal-set)
        published (some (fn [{:card-set/keys [number published-on]}]
                          (when (= earliest number) published-on))
                        card-sets)]
    (when earliest
      {:from  :card
       :where [:and
               [:is-not :card/price-cents nil]
               [:not-like :card/type "%Token%"]
               [:exists
                {:select [1]
                 :from   :printing
                 :join   [:card-set [:= :card-set/id :printing/card-set-id]]
                 :where  [:and
                          [:= :printing/card-id :card/id]
                          (legal-printing-where earliest published)]}]]})))

(def external-uid-query
  "Correlated subquery for a card's starwarsunlimited.com ID: its Standard
  printing from the newest set, or else its newest printing."
  {:select   [:printing/external-uid]
   :from     :printing
   :join     [:card-set [:= :card-set/id :printing/card-set-id]]
   :where    [:and
              [:= :printing/card-id :card/id]
              [:is-not :printing/external-uid nil]]
   :order-by [[[:case [:= :printing/variant "Standard"] 0 :else 1]]
              [[:coalesce :card-set/published-on ""] :desc]
              [:card-set/number :desc]]
   :limit    1})

;;;; Search ====================================================================
;; Fuzzy-find-style (fzf-like) matching. A search is split into terms on
;; whitespace; text matches when each term's characters appear in it in order.
;; Matches score higher when the characters are consecutive or start words.

(defn search-terms [query]
  (filterv seq (str/split (str/lower-case (str query)) #"\s+")))

(defn like-pattern
  "Returns a SQL LIKE pattern, with \\ as the escape character, matching text
  that contains `term`'s characters in order."
  [term]
  (apply str "%" (mapv #(str (when (#{\% \_ \\} %) "\\") % "%") term)))

(defn- word-start? [text i]
  (or (zero? i)
      (not (Character/isLetterOrDigit (.charAt ^String text (dec i))))))

(defn- char-score
  "Scores matching the character at index `i`, given the index of the
  previously matched character."
  [text i prev]
  (cond-> 1
    (= prev (dec i))                    (+ 2)
    (word-start? text i)                (+ 3)
    (and (some? prev) (< (inc prev) i)) (- 1)))

(defn- score-from
  "Scores a greedy match of `term` in `text` that starts at index `start`, or
  returns nil if the rest of the term doesn't match."
  [^String text ^String term start]
  (loop [ti 0 i start prev nil score 0]
    (cond
      (= ti (count term))            score
      (= i (count text))             nil
      (= (nth text i) (nth term ti)) (recur (inc ti) (inc i) i (+ score (char-score text i prev)))
      :else                          (recur ti (inc i) prev score))))

(defn- term-score [text term]
  (let [first-char (.charAt ^String term 0)
        scores     (into []
                         (keep #(when (= first-char (.charAt ^String text %))
                                  (score-from text term %)))
                         (range (count text)))]
    (when (seq scores)
      (apply max scores))))

(defn search-score
  "Returns how well `text` matches all `terms` (higher is better), or nil if
  any term doesn't match."
  [terms text]
  (let [text   (str/lower-case text)
        scores (mapv #(term-score text %) terms)]
    (when (every? some? scores)
      (reduce + scores))))

(defn rank-matches
  "Returns the items whose `text-fn` matches all `terms`, best match first.
  Equally good matches keep their original order."
  [terms text-fn items]
  (->> items
       (into [] (keep (fn [item]
                        (when-some [s (search-score terms (text-fn item))]
                          [s item]))))
       (sort-by (comp - first))
       (mapv second)))

;;;; Resolvers =================================================================

(def ^:private card-columns
  [:card/id :card/type :card/price-cents [external-uid-query :external-uid]])

(defn- search-where
  "Adds a condition to `query` for each search term, so SQL narrows the cards
  down to fuzzy matches before they're ranked."
  [query terms]
  (update query :where into (mapv (fn [term]
                                    [:like :card/id [:escape (like-pattern term) "\\"]])
                                  terms)))

(defn- page-of [cards number]
  (let [start (min (count cards) (* page-size (dec number)))]
    (subvec cards start (min (count cards) (+ start page-size)))))

(defresolver card-page
  {:output [{:request/card-page
             [:card-page/number
              :card-page/page-count
              :card-page/search
              {:card-page/cards [:card/id
                                 :card/type
                                 :card/price-cents
                                 [:? :card/external-uid]]}]}]}

  (fn [{:keys [biff.datastar/tab-id]} _]
    (cond-> {:card-sets [:biff.sqlite.fx/execute
                         {:select [:card-set/id
                                   :card-set/number
                                   :card-set/published-on]
                          :from   :card-set}]
             :metas     [:biff.sqlite.fx/execute
                         {:select [:meta/set-id]
                          :from   :meta
                          :where  [:= :meta/is-current true]}]}
      tab-id (assoc :tabs [:biff.sqlite.fx/execute
                           {:select [:tab-state/card-search :tab-state/card-page]
                            :from   :tab-state
                            :where  [:= :tab-state/id tab-id]}])))

  (fn [_ {:keys [card-sets metas tabs]}]
    (let [tab        (first tabs)
          number     (max 1 (or (:tab-state/card-page tab) 1))
          search-str (str/trim (str (:tab-state/card-search tab)))
          terms      (search-terms search-str)
          query      (legal-cards-query card-sets (:meta/set-id (first metas)))]
      (cond
        (nil? query)
        {:biff.fx/return
         {:request/card-page {:card-page/number     1
                              :card-page/page-count 0
                              :card-page/search     search-str
                              :card-page/cards      []}}}

        (seq terms)
        {:number     number
         :search-str search-str
         :terms      terms
         :matches    [:biff.sqlite.fx/execute
                      (assoc (search-where query terms)
                             :select card-columns
                             :order-by [[:card/price-cents :desc] [:card/id :asc]])]}

        :else
        {:number     number
         :search-str search-str
         :card-count [:biff.sqlite.fx/execute
                      (assoc query :select [[[:count :*] :n]])]
         :cards      [:biff.sqlite.fx/execute
                      (assoc query
                             :select card-columns
                             :order-by [[:card/price-cents :desc] [:card/id :asc]]
                             :limit page-size
                             :offset (* page-size (dec number)))]})))

  (fn [_ {:keys [number search-str terms matches card-count cards]}]
    (let [ranked     (when terms
                       (rank-matches terms :card/id matches))
          card-count (if terms
                       (count ranked)
                       (:n (first card-count)))]
      {:request/card-page
       {:card-page/number     number
        :card-page/page-count (quot (+ card-count (dec page-size)) page-size)
        :card-page/search     search-str
        :card-page/cards      (mapv #(set/rename-keys % {:printing/external-uid
                                                         :card/external-uid})
                                    (if terms
                                      (page-of ranked number)
                                      cards))}})))

(defresolver card
  {:output [{:request/card [:card/id]}]}

  (fn [{:keys [params]} _]
    (if-some [card-id (:card-id params)]
      [:biff.sqlite.fx/execute
       {:select [:card/id] :from :card :where [:= :card/id card-id]}]
      {:biff.fx/return {}}))

  (fn [_ rows]
    (when-some [row (first rows)]
      {:request/card row})))

(defresolver page
  {:output [:request/page]}
  [{:keys [params]} _]
  (let [n (:page params)]
    (when (pos-int? n)
      {:request/page n})))

(defresolver card-search
  {:output [:request/card-search]}
  [{:keys [biff.datastar/signals]} _]
  (let [s (:tab/card-search signals)]
    (when (string? s)
      {:request/card-search (subs s 0 (min (count s) schema/max-search-length))})))

(def unsubscribe-link-days 30)

(defresolver notification
  {:output [{:request/notification [:notification/id]}]}

  (fn [{:keys [path-params] :biff.fx/keys [now]} _]
    (let [token (:token path-params)]
      (if (uuid? token)
        [:biff.sqlite.fx/execute
         {:select [:notification/id]
          :from   :notification
          :where  [:and
                   [:= :notification/token token]
                   [:>= :notification/created-at
                    (tick/<< now (tick/new-duration unsubscribe-link-days :days))]]}]
        {:biff.fx/return {}})))

  (fn [_ rows]
    (when-some [row (first rows)]
      {:request/notification row})))

(defresolver email-notifications
  {:output [:request/email-notifications]}
  [{:keys [params]} _]
  (let [enabled (:enabled params)]
    (when (boolean? enabled)
      {:request/email-notifications enabled})))

(defresolver selected-recommendations
  {:output [:request/selected-recommendations]}
  [{:keys [biff.datastar/signals]} _]
  (let [ids (:admin/selected signals)]
    (when (and (sequential? ids) (every? string? ids))
      {:request/selected-recommendations (set ids)})))

(defresolver quantity
  {:output [:request/quantity]}
  [{:keys [params]} _]
  (let [n (:quantity params)]
    (when (and (int? n) (<= 0 n schema/max-owned-quantity))
      {:request/quantity n})))

(def module
  {:biff.graph/resolvers [card-page
                          card
                          page
                          card-search
                          notification
                          email-notifications
                          selected-recommendations
                          quantity]})
