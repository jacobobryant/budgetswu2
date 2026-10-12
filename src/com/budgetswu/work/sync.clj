(ns com.budgetswu.work.sync
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.biffweb.fx :refer [defpipeline]]
            [com.budgetswu.lib.ranking :as ranking]
            [com.budgetswu.lib.recommendations :as recommendations]
            [tick.core :as tick])
  (:import [java.net URLEncoder]))

(def swuapi "https://api.swuapi.com")
(def tcgcsv "https://tcgcsv.com/tcgplayer/79")
(def initial-watermark "1970-01-01T00:00:00Z")

(defpipeline execute
  (fn [_ statement]
    [:biff.sqlite.fx/execute statement]))

(defpipeline execute-tx
  (fn [_ statements]
    [:biff.sqlite.fx/execute-tx statements]))

(defpipeline graph-query
  (fn [_ query]
    [:biff.graph.fx/query query]))

(defpipeline write-home
  (fn [_ html]
    [:com.budgetswu.fx/write-home-html html]))

(defpipeline fetch-json
  (fn [_ url api-key]
    [:biff.fx/http
     {:url     url
      :method  :get
      :headers (cond-> {"User-Agent" "Mozilla/5.0 BudgetSWU"}
                 (seq api-key)
                 (assoc "Authorization" (str "Bearer " api-key)))}])

  (fn [_ response]
    (let [body (json/parse-string (:body response) true)]
      (when (false? (:success body))
        (throw (ex-info "Data provider returned an error"
                        {:url    (:url response)
                         :errors (:errors body)})))
      body)))

(defn- query-string [params]
  (str/join "&"
            (mapv (fn [[k v]]
                    (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))
                  params)))

(defn- url [base path params]
  (str base path (when (seq params) (str "?" (query-string params)))))

(defn- upsert [table id-key row]
  {:insert-into   table
   :values        [row]
   :on-conflict   [id-key]
   :do-update-set (vec (keys row))})

(defn- state-value [ctx state-id field]
  (let [states (:global/sync-states
                (graph-query ctx
                             [{:global/sync-states
                               [:sync-state/id
                                [:? :sync-state/watermark]
                                [:? :sync-state/attempted-at]]}]))]
    (get (first (filterv #(= state-id (:sync-state/id %)) states))
         field)))

(defn- save-state [ctx state-id field value]
  (execute ctx (upsert :sync-state :sync-state/id
                       (assoc {:sync-state/id state-id} field value))))

(defn- current-meta [ctx]
  (fetch-json ctx (str swuapi "/metas/current?format=premiere") nil))

(defn- date-in-meta? [meta tournament-date]
  (let [date (some-> tournament-date (subs 0 10))]
    (and date
         (<= (compare (:start meta) date) 0)
         (or (nil? (:end meta))
             (neg? (compare date (:end meta)))))))

(defn- reset-current-meta [ctx meta]
  (let [prior   (first (filterv :meta/is-current
                                (:global/metas
                                 (graph-query ctx
                                              [{:global/metas
                                                [:meta/id
                                                 :meta/is-current]}]))))
        changed (not= (:meta/id prior) (:id meta))]
    (when changed
      (execute-tx ctx [{:delete-from :sync-state
                        :where       [:in :sync-state/id
                                      ["tournaments" "decklists" "standings"]]}
                       {:update :meta :set {:meta/is-current false}}]))
    (execute ctx
             (upsert :meta :meta/id
                     {:meta/id         (:id meta)
                      :meta/start-date (:start meta)
                      :meta/end-date   (:end meta)
                      :meta/set-id     (:set meta)
                      :meta/is-current true}))
    changed))

(defn- sync-feed [ctx path result-key state-id page-size extra-params f]
  (let [since (or (state-value ctx state-id :sync-state/watermark)
                  initial-watermark)]
    (loop [after          nil
           high-watermark since]
      (let [params         (cond-> (merge {:since since :limit page-size}
                                          extra-params)
                             after (assoc :after after))
            response       (fetch-json ctx (url swuapi path params) nil)
            rows           (get response result-key)
            next-cursor    (get-in response [:pagination :next_cursor])
            next-watermark (reduce (fn [acc row]
                                     (let [updated (:updated_at row)]
                                       (if (and updated
                                                (pos? (compare updated acc)))
                                         updated acc)))
                                   high-watermark rows)]
        (when-not (vector? rows)
          (throw (ex-info "Invalid data feed" {:path path})))
        (run! #(f %) rows)
        (if (and next-cursor (= page-size (count rows)))
          (recur next-cursor next-watermark)
          (save-state ctx state-id :sync-state/watermark next-watermark))))))

(defn- sync-export [ctx entity f]
  (loop [cursor nil]
    (let [response    (fetch-json ctx
                                  (url swuapi (str "/export/" entity)
                                       (cond-> {:limit 5000}
                                         cursor (assoc :cursor cursor)))
                                  nil)
          rows        (:data response)
          next-cursor (:next_cursor response)]
      (when-not (vector? rows)
        (throw (ex-info "Invalid export" {:entity entity})))
      (run! f rows)
      (when (:has_more response)
        (when-not (and (seq rows) (seq next-cursor))
          (throw (ex-info "Invalid export cursor" {:entity entity})))
        (recur next-cursor)))))

(defn card-id
  "Returns the ID shared by all printings of a card: its name and subtitle, in
  the same format decklists use."
  [{:keys [name subtitle]}]
  (if (seq subtitle)
    (str name ", " subtitle)
    name))

(defn set-number
  "Returns the set number encoded in the first two digits of a printing's
  serial code, or nil for promo sets without one."
  [serial-code]
  (some->> serial-code (re-find #"^\d\d") parse-long))

(defn- sync-cards [ctx]
  (sync-feed
   ;; A new state ID forces a full resync, e.g. after adding printing columns.
   ctx "/cards" :cards "cards-v4" 500 {}
   (fn [printing]
     (let [id (card-id printing)]
       (execute-tx
        ctx
        (into [(upsert :card :card/id {:card/id id :card/type (:type printing)})
               (upsert :card-set :card-set/id
                       {:card-set/id     (:set_code printing)
                        :card-set/number (set-number (:serial_code printing))})
               (upsert :printing :printing/id
                       {:printing/id               (:uuid printing)
                        :printing/card-id          id
                        :printing/card-set-id      (:set_code printing)
                        :printing/collector-number (:collector_number printing)
                        :printing/variant          (:variant_type printing)
                        :printing/external-uid     (:external_uid printing)})
               {:delete-from :card-aspect
                :where       [:= :card-aspect/card-id id]}]
              (mapv (fn [aspect]
                      {:insert-into :card-aspect
                       :values      [{:card-aspect/id      (str id ":" aspect)
                                      :card-aspect/card-id id
                                      :card-aspect/aspect  aspect}]})
                    (set (:aspects printing)))))))))

(defn- sync-tournaments [ctx meta]
  (sync-export
   ctx "tournaments"
   (fn [tournament]
     (when (and (= "Premier" (:format tournament))
                (date-in-meta? meta (:date tournament))
                (not (pos? (compare (subs (:date tournament) 0 10)
                                    (str (tick/today))))))
       (execute ctx
                (upsert :tournament :tournament/id
                        {:tournament/id      (:uuid tournament)
                         :tournament/name    (or (:display_name tournament)
                                                 (:name tournament))
                         :tournament/held-at (:date tournament)

                         :tournament/player-count
                         (:player_count tournament)

                         :tournament/format (:format tournament)}))))))

(defn- tournament-ids [ctx meta]
  (into #{}
        (comp (filter #(date-in-meta? meta (:tournament/held-at %)))
              (map :tournament/id))
        (:global/tournaments
         (graph-query ctx
                      [{:global/tournaments
                        [:tournament/id :tournament/held-at]}]))))

(declare delete-entity)

(defn- prune-old-tournaments [ctx meta]
  (let [old-ids (into []
                      (comp (remove #(date-in-meta?
                                      meta (:tournament/held-at %)))
                            (map :tournament/id))
                      (:global/tournaments
                       (graph-query ctx
                                    [{:global/tournaments
                                      [:tournament/id
                                       :tournament/held-at]}])))]
    (run! #(delete-entity ctx "tournament" %) old-ids)))

(defn- main-cards [decklist]
  (let [cards (:decklist decklist)]
    (if (vector? cards)
      (filterv (complement :sideboard) cards)
      [])))

(defn- player-names [ctx]
  (let [names (atom {})]
    (sync-export ctx "players"
                 (fn [player]
                   (swap! names assoc (:uuid player) (:name player))))
    @names))

(defn- card-types [ctx]
  (into {}
        (map (juxt :card/id :card/type))
        (:global/leaders-and-bases
         (graph-query ctx
                      [{:global/leaders-and-bases [:card/id :card/type]}]))))

(defn- card-of-type [types cards card-type]
  (first (filterv #(or (= card-type (:type %))
                       (= card-type (get types (:name %))))
                  cards)))

(defn- sync-decklists [ctx valid-tournament-ids names]
  (let [deck-ids (atom {})
        types    (card-types ctx)]
    (sync-export
     ctx "decklists"
     (fn [decklist]
       (when (contains? valid-tournament-ids (:tournament_uuid decklist))
         (let [deck-id (:uuid decklist)
               cards   (main-cards decklist)
               leader  (card-of-type types cards "Leader")
               base    (card-of-type types cards "Base")]
           (swap! deck-ids assoc [(:tournament_uuid decklist)
                                  (:player_uuid decklist)] deck-id)
           (execute-tx
            ctx
            (into [(upsert :decklist :decklist/id
                           {:decklist/id       deck-id
                            :decklist/melee-id (:melee_id decklist)

                            :decklist/tournament-id
                            (:tournament_uuid decklist)

                            :decklist/player-name
                            (get names (:player_uuid decklist))

                            :decklist/leader-id (:name leader)
                            :decklist/base-id   (:name base)

                            :decklist/format "Premier"})
                   {:delete-from :deck-card
                    :where       [:= :deck-card/decklist-id deck-id]}]
                  (mapv (fn [{id :name collector :collector_number :as card}]
                          {:insert-into :deck-card
                           :values      [{:deck-card/id          (str deck-id ":" collector)
                                          :deck-card/decklist-id deck-id
                                          :deck-card/card-id     id
                                          :deck-card/quantity    (:count card)}]})
                        (filterv #(and (:name %) (:collector_number %))
                                 cards))))))))
    @deck-ids))

(defn- sync-standings [ctx valid-tournament-ids deck-ids]
  (sync-export
   ctx "standings"
   (fn [standing]
     (when (contains? valid-tournament-ids (:tournament_uuid standing))
       (execute ctx
                (upsert :standing :standing/id
                        {:standing/id (:uuid standing)

                         :standing/decklist-id
                         (or (:decklist_uuid standing)
                             (get deck-ids [(:tournament_uuid standing)
                                            (:player_uuid standing)]))

                         :standing/tournament-id
                         (:tournament_uuid standing)

                         :standing/final-placement
                         (:final_placement standing)}))))))

(defn- event-time [event]
  (or (:merged_at event) (:deleted_at event)))

(defn- sync-events [ctx path result-key state-id f]
  (let [since    (or (state-value ctx state-id :sync-state/watermark)
                     initial-watermark)
        response (fetch-json ctx (url swuapi path {:since since}) nil)
        events   (get response result-key)]
    (when-not (vector? events)
      (throw (ex-info "Invalid event feed" {:path path})))
    (run! f events)
    (save-state ctx state-id :sync-state/watermark
                (reduce (fn [acc event]
                          (let [updated (event-time event)]
                            (if (and updated
                                     (pos? (compare updated acc)))
                              updated acc)))
                        since events))))

(defn- delete-entity [ctx entity-type entity-id]
  (case entity-type
    "decklist" (execute-tx ctx
                           [{:delete-from :deck-card
                             :where       [:= :deck-card/decklist-id entity-id]}
                            {:delete-from :standing
                             :where       [:= :standing/decklist-id entity-id]}
                            {:delete-from :decklist
                             :where       [:= :decklist/id entity-id]}])
    "tournament" (let [deck-ids
                       (into []
                             (comp (filter #(= entity-id
                                               (:decklist/tournament-id %)))
                                   (map :decklist/id))
                             (:global/decklists
                              (graph-query ctx
                                           [{:global/decklists
                                             [:decklist/id
                                              :decklist/tournament-id]}])))]
                   (run! #(delete-entity ctx "decklist" %) deck-ids)
                   (execute-tx ctx
                               [{:delete-from :standing
                                 :where       [:= :standing/tournament-id entity-id]}
                                {:delete-from :tournament
                                 :where       [:= :tournament/id entity-id]}]))
    "standing" (execute ctx {:delete-from :standing
                             :where       [:= :standing/id entity-id]})
    "card" (execute ctx {:delete-from :printing
                         :where       [:= :printing/id entity-id]})
    nil))

(defn- sync-merges-and-deletions [ctx]
  (sync-events
   ctx "/merges" :merges "merges"
   (fn [event]
     (let [old-id (:from_uuid event)
           new-id (:to_uuid event)]
       (case (:entity_type event)
         "decklist" (do
                      (execute ctx {:update :standing
                                    :set    {:standing/decklist-id new-id}
                                    :where  [:= :standing/decklist-id old-id]})
                      (execute ctx {:delete-from :deck-card
                                    :where       [:= :deck-card/decklist-id old-id]})
                      (execute ctx {:delete-from :decklist
                                    :where       [:= :decklist/id old-id]}))
         "tournament" (do
                        (execute ctx {:update :decklist
                                      :set    {:decklist/tournament-id new-id}
                                      :where  [:= :decklist/tournament-id old-id]})
                        (execute ctx {:update :standing
                                      :set    {:standing/tournament-id new-id}
                                      :where  [:= :standing/tournament-id old-id]})
                        (execute ctx {:delete-from :tournament
                                      :where       [:= :tournament/id old-id]}))
         "standing" (delete-entity ctx "standing" old-id)
         nil))))
  (sync-events
   ctx "/deletions" :deletions "deletions"
   (fn [event]
     (delete-entity ctx (:entity_type event) (:swuapi_uuid event)))))

(defn- product-collector-number [group product]
  (when-let [number (some (fn [field]
                            (when (= "Number" (:name field))
                              (:value field)))
                          (:extendedData product))]
    (when-let [[_ digits] (re-find #"^(\d+)" number)]
      (str (:abbreviation group) "_"
           (format "%03d" (Long/parseLong digits))))))

(defn- nonfoil-price [prices]
  (let [cents (->> prices
                   (filterv #(and (#{"Normal" "Non-Foil"}
                                   (:subTypeName %))
                                  (number? (:lowPrice %))))
                   (mapv #(long (Math/round (* 100.0 (:lowPrice %))))))]
    (when (seq cents)
      (apply min cents))))

(defn- group-prices
  "Returns a map from collector number to the lowest non-foil price in cents
  for a tcgcsv group."
  [ctx group]
  (let [root       (str tcgcsv "/" (:groupId group))
        products   (:results (fetch-json ctx (str root "/products") nil))
        prices     (:results (fetch-json ctx (str root "/prices") nil))
        by-product (group-by :productId prices)]
    (reduce (fn [acc product]
              (let [collector (product-collector-number group product)
                    price     (nonfoil-price
                               (get by-product (:productId product)))]
                (if (and collector price)
                  (update acc collector #(min (or % price) price))
                  acc)))
            {}
            products)))

(defn card-prices
  "Returns a map from card ID to the lowest price of any of its printings.
  `printings` are the standard printings used to map collector numbers to
  cards."
  [printings collector-prices]
  (reduce (fn [acc {:printing/keys [card-id collector-number]}]
            (if-some [price (get collector-prices collector-number)]
              (update acc card-id #(min (or % price) price))
              acc))
          {}
          printings))

(defn- sync-prices [ctx]
  (let [groups (:results (fetch-json ctx (str tcgcsv "/groups") nil))

        {:global/keys [card-sets standard-printings]}
        (graph-query ctx
                     [{:global/card-sets [:card-set/id]}
                      {:global/standard-printings
                       [:printing/card-id :printing/collector-number]}])

        known-sets (into #{} (map :card-set/id) card-sets)
        groups     (filterv #(contains? known-sets (:abbreviation %)) groups)
        ;; Some abbreviations are shared by several groups; keep the earliest.
        published  (into {}
                         (map (juxt :abbreviation #(subs (:publishedOn %) 0 10)))
                         (sort-by :publishedOn #(compare %2 %1) groups))
        prices     (card-prices standard-printings
                                (apply merge-with min
                                       (mapv #(group-prices ctx %) groups)))]
    (execute-tx ctx
                (into
                 (mapv (fn [[set-id date]]
                         {:update :card-set
                          :set    {:card-set/published-on date}
                          :where  [:= :card-set/id set-id]})
                       published)
                 (cons {:update :card :set {:card/price-cents nil}}
                       (mapv (fn [[id cents]]
                               {:update :card
                                :set    {:card/price-cents cents}
                                :where  [:= :card/id id]})
                             prices))))))

(defn- update-recommendations [ctx]
  (let [front (:global/front
               (graph-query ctx
                            [{:global/front ranking/fields}]))]
    (execute-tx
     ctx
     (into [{:delete-from :recommendation}]
           (map-indexed
            (fn [rank deck]
              {:insert-into :recommendation
               :values      [{:recommendation/id   (:recommendation/id deck)
                              :recommendation/rank rank}]}))
           front))
    front))

(defn sync-now [ctx]
  (let [meta (current-meta ctx)]
    (reset-current-meta ctx meta)
    (sync-cards ctx)
    (sync-tournaments ctx meta)
    (let [ids      (tournament-ids ctx meta)
          names    (player-names ctx)
          deck-ids (sync-decklists ctx ids names)]
      (sync-standings ctx ids deck-ids))
    (sync-merges-and-deletions ctx)
    (sync-prices ctx)
    (let [front (update-recommendations ctx)
          html  (:body (recommendations/home-response ctx front))]
      (write-home ctx html)
      (prune-old-tournaments ctx meta)
      (count front))))

(defn- due? [last-attempt]
  (or (nil? last-attempt)
      (tick/>= (tick/now)
               (tick/>> (tick/instant last-attempt)
                        (tick/new-duration 12 :hours)))))

(defn sync-if-due [ctx]
  (when (due? (state-value ctx "job" :sync-state/attempted-at))
    (save-state ctx "job" :sync-state/attempted-at
                (str (tick/now)))
    (try
      (sync-now ctx)
      (catch Exception e
        (log/error e "Decklist sync failed")))))

(def module
  {:biff.background/tasks
   [{:schedule (fn []
                 (iterate #(tick/>> % (tick/new-duration 15 :minutes))
                          (tick/now)))
     :task     sync-if-due}]})
