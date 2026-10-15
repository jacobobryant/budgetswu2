(ns com.budgetswu.work.sync
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.biffweb.fx :refer [defmachine defpipeline]]
            [com.budgetswu.lib.email :as email]
            [com.budgetswu.lib.notification :as notification]
            [com.budgetswu.lib.recommendations :as recommendations]
            [tick.core :as tick])
  (:import [java.net URLEncoder]))

(def swu-cards "https://admin.starwarsunlimited.com/api/card-list")
(def melee "https://melee.gg")
(def tcgcsv "https://tcgcsv.com/tcgplayer/79")

(def melee-premier-format-id "d312b6cd-1f2d-4d49-887c-8596673c43dd")

;; Decklists are re-searched this far back on each sync, since tournaments can
;; end and publish decklists after the date they're listed under.
(def melee-lookback-days 7)

(def sync-interval (tick/new-duration 12 :hours))

(def card-page-size 250)

(def decklist-page-size 500)

(defn user-agent
  "melee.gg rejects some user agents, including ones without a URL."
  [{:com.budgetswu/keys [base-url]}]
  (str "Mozilla/5.0 (compatible; BudgetSWU/1.0"
       (when base-url (str "; +" base-url))
       ")"))

(defn http-request
  "Returns a :biff.fx/http request that identifies BudgetSWU."
  [ctx req]
  (-> req
      (update :method #(or % :get))
      (assoc-in [:headers "User-Agent"] (user-agent ctx))))

(defn- parse-json [response]
  (json/parse-string (:body response) true))

(defn cookie-header
  "Returns a Cookie header value from Set-Cookie header values."
  [set-cookies]
  (str/join "; "
            (mapv #(first (str/split % #";"))
                  (if (string? set-cookies) [set-cookies] set-cookies))))

(def ^:private sign-in-token-pattern
  #"name=\"__RequestVerificationToken\" type=\"hidden\" value=\"([^\"]+)\"")

(defn- query-string [params]
  (let [encode #(URLEncoder/encode (str %) "UTF-8")]
    (str/join "&"
              (mapv (fn [[k v]]
                      (str (encode (if (keyword? k) (name k) k)) "=" (encode v)))
                    params))))

(defn sign-in-request
  "Returns melee.gg's sign-in request. The form is sent as bytes so the
  credentials don't appear in biff.fx error traces."
  [{:com.budgetswu/keys [melee-email melee-password] :as ctx} token cookies]
  (when-not (and melee-email melee-password)
    (throw (ex-info "MELEE_EMAIL and MELEE_PASSWORD must be set" {})))
  (let [form (query-string {"__RequestVerificationToken" token
                            "ReturnUrl"                  "/"
                            "Email"                      melee-email
                            "Password"                   (force melee-password)})]
    (http-request ctx {:url     (str melee "/Account/SignInPassword")
                       :method  :post
                       :headers {"Content-Type"     "application/x-www-form-urlencoded"
                                 "Cookie"           (cookie-header cookies)
                                 "X-Requested-With" "XMLHttpRequest"}
                       :body    (.getBytes ^String form "UTF-8")})))

(defn- url [base path params]
  (str base path (when (seq params) (str "?" (query-string params)))))

(defn- upsert [table id-key row]
  {:insert-into   table
   :values        [row]
   :on-conflict   [id-key]
   :do-update-set (vec (keys row))})

(defn- save-state [state-id field value]
  (upsert :sync-state :sync-state/id (assoc {:sync-state/id state-id} field value)))

(defn state-value [sync-states state-id field]
  (some #(when (= state-id (:sync-state/id %)) (get % field)) sync-states))

(def ^:private sync-states-query
  [{:global/sync-states
    [:sync-state/id [:? :sync-state/watermark] [:? :sync-state/attempted-at]]}])

(defn utc-date [instant]
  (tick/date (tick/in instant "UTC")))

(defn due? [last-attempt now]
  (or (nil? last-attempt)
      (tick/>= now (tick/>> (tick/instant last-attempt) sync-interval))))

;;;; Cards =====================================================================

(defn card-id
  "Returns the ID shared by all printings of a card: its title and subtitle, in
  the same format decklists use."
  [{:keys [title subtitle]}]
  (if (seq subtitle)
    (str title ", " subtitle)
    title))

(defn set-number
  "Returns the set number encoded in the first two digits of a printing's
  serial code, or nil for promo sets without one."
  [serial-code]
  (some->> serial-code (re-find #"^\d\d") parse-long))

(defn- relation-name [relation]
  (get-in relation [:data :attributes :name]))

(defn card-printing
  "Returns the card, set, printing, and aspects for a printing from
  starwarsunlimited.com, or nil if it has no set or number. Set codes and
  collector numbers follow tcgcsv's conventions: the Hyperspace promos from sets
  1-3 (e.g. serial 0102001) are in their own set (e.g. SORP), and tokens'
  numbers are prefixed with T."
  [{:keys [id attributes]}]
  (let [{:keys [title subtitle cardNumber serialCode cardUid]} attributes

        code      (get-in attributes [:expansion :data :attributes :code])
        card-type (relation-name (:type attributes))
        set-id    (if (and serialCode
                           (re-matches #"\d\d02\d{3}" serialCode)
                           (< cardNumber 100))
                    (str code "P")
                    code)
        id*       (card-id {:title title :subtitle subtitle})]
    (when (and code cardNumber)
      {:card     {:card/id id* :card/type card-type}
       :card-set {:card-set/id     set-id
                  :card-set/number (set-number serialCode)}
       :printing {:printing/id               (str id)
                  :printing/card-id          id*
                  :printing/card-set-id      set-id
                  :printing/collector-number (format "%s_%s%03d"
                                                     set-id
                                                     (if (str/includes? (str card-type) "Token")
                                                       "T"
                                                       "")
                                                     cardNumber)
                  :printing/variant          (get-in attributes
                                                     [:variantTypes :data 0
                                                      :attributes :name])
                  :printing/external-uid     cardUid}
       :aspects  (into #{}
                       (map #(get-in % [:attributes :name]))
                       (get-in attributes [:aspects :data]))})))

(defn- card-printing-tx [{:keys [card card-set printing aspects]}]
  (into [(upsert :card :card/id card)
         (upsert :card-set :card-set/id card-set)
         (upsert :printing :printing/id printing)
         {:delete-from :card-aspect
          :where       [:= :card-aspect/card-id (:card/id card)]}]
        (mapv (fn [aspect]
                {:insert-into :card-aspect
                 :values      [{:card-aspect/id      (str (:card/id card) ":" aspect)
                                :card-aspect/card-id (:card/id card)
                                :card-aspect/aspect  aspect}]})
              aspects)))

(defn latest-update
  "Returns whichever of two updatedAt timestamps is later."
  [a b]
  (cond
    (nil? a)                                    b
    (nil? b)                                    a
    (tick/> (tick/instant b) (tick/instant a)) b
    :else                                       a))

(defn cards-url [since page]
  (url swu-cards ""
       (cond-> [["locale" "en"]
                ["sort[0]" "updatedAt:asc"]
                ["sort[1]" "id:asc"]
                ["pagination[page]" page]
                ["pagination[pageSize]" card-page-size]]
         since (conj ["filters[updatedAt][$gt]" since]))))

;;;; Meta ======================================================================

(defn current-meta
  "Returns the current meta, which starts when the latest numbered set is
  released."
  [card-sets today]
  (when-let [card-set (->> card-sets
                           (filterv #(and (:card-set/number %)
                                          (:card-set/published-on %)
                                          (tick/<= (tick/date (:card-set/published-on %))
                                                   today)))
                           (sort-by (juxt (comp - :card-set/number)
                                          (comp tick/date :card-set/published-on)
                                          :card-set/id))
                           first)]
    {:id    (:card-set/id card-set)
     :start (:card-set/published-on card-set)}))

;;;; Decklists =================================================================

(def ^:private search-columns
  ["DecklistName"
   "Game"
   "FormatId"
   "FormatName"
   "OwnerDisplayName"
   "TournamentName"
   "SortDate"
   "TeamRank"
   "TeamMatchWins"
   "OrganizationName"
   "Records"
   "Archetypes"
   "TournamentTags"
   "LeaderName"
   "SecondaryName"])

(defn decklist-search-params
  "Returns form params for melee.gg's decklist search (a DataTables endpoint)
  for premier decklists from tournaments held between two dates, inclusive."
  [start length from to]
  (let [column-search {"Game"     "StarWarsUnlimited"
                       "FormatId" melee-premier-format-id
                       "SortDate" (str from "|" to)}]
    (into {"draw"             "1"
           "start"            (str start)
           "length"           (str length)
           "search[value]"    ""
           "search[regex]"    "false"
           "order[0][column]" (str (.indexOf ^java.util.List search-columns "SortDate"))
           "order[0][dir]"    "desc"}
          (comp (map-indexed vector)
                (mapcat (fn [[i column]]
                          (let [k #(str "columns[" i "]" %)]
                            [[(k "[data]") column]
                             [(k "[name]") column]
                             [(k "[searchable]") "true"]
                             [(k "[orderable]") "true"]
                             [(k "[search][value]") (get column-search column "")]
                             [(k "[search][regex]") "false"]]))))
          search-columns)))

(defn- decklist-search-request [ctx cookie start from to]
  (http-request ctx {:url         (str melee "/Decklist/SearchDecklists")
                     :method      :post
                     :headers     {"Cookie"           cookie
                                   "X-Requested-With" "XMLHttpRequest"}
                     :form-params (decklist-search-params start decklist-page-size from to)}))

(def ^:private deck-components
  "melee.gg's component codes for the parts of a decklist we price. 99 is the
  sideboard."
  {0 :main 6 :leader 7 :base})

(defn melee-decklist
  "Returns the tournament, decklist, and main deck cards (including the leader
  and base) for a decklist search result."
  [{:keys [Guid TournamentId TournamentName TournamentStartDate OwnerDisplayName
           TeamRank Records]}]
  (let [cards   (filterv #(contains? deck-components (:c %)) Records)
        card-of #(some (fn [card]
                         (when (= % (deck-components (:c card)))
                           (card-id {:title (:n card) :subtitle (:s card)})))
                       cards)]
    {:tournament {:tournament/id      (str TournamentId)
                  :tournament/name    TournamentName
                  :tournament/held-at TournamentStartDate
                  :tournament/format  "Premier"}
     :decklist   {:decklist/id            Guid
                  :decklist/tournament-id (str TournamentId)
                  :decklist/player-name   OwnerDisplayName
                  :decklist/leader-id     (card-of :leader)
                  :decklist/base-id       (card-of :base)
                  :decklist/format        "Premier"
                  :decklist/placement     TeamRank}
     :cards      (mapv (fn [[id quantity]]
                         {:deck-card/id          (str Guid ":" id)
                          :deck-card/decklist-id Guid
                          :deck-card/card-id     id
                          :deck-card/quantity    quantity})
                       (reduce (fn [acc {:keys [n s q]}]
                                 (update acc
                                         (card-id {:title n :subtitle s})
                                         (fnil + 0)
                                         q))
                               {}
                               cards))}))

(defn known-cards?
  "Returns true if all of a decklist's cards are in `card-ids`."
  [card-ids {:keys [decklist cards]}]
  (every? #(or (nil? %) (contains? card-ids %))
          (into [(:decklist/leader-id decklist) (:decklist/base-id decklist)]
                (map :deck-card/card-id)
                cards)))

(defn- decklist-tx [{:keys [decklist cards]}]
  (into [(upsert :decklist :decklist/id decklist)
         {:delete-from :deck-card
          :where       [:= :deck-card/decklist-id (:decklist/id decklist)]}]
        (mapv (fn [card]
                {:insert-into :deck-card :values [card]})
              cards)))

(defn new-tournaments
  "Returns the distinct tournaments of `decklists` that aren't in `known`."
  [known decklists]
  (:tournaments
   (reduce (fn [{:keys [seen] :as acc} {:keys [tournament]}]
             (let [id (:tournament/id tournament)]
               (if (contains? seen id)
                 acc
                 (-> acc
                     (update :seen conj id)
                     (update :tournaments conj tournament)))))
           {:seen known :tournaments []}
           decklists)))

(defn enrolled-players
  "Returns the number of players from a melee.gg tournament page."
  [html]
  (some->> html
           (re-find #"(\d+)(?: of \d+)? Enrolled Players?")
           second
           parse-long))

(defn decklist-search-start
  "Returns the first date to search for decklists: the meta's start for a full
  sync, otherwise the watermark."
  [game-meta watermark]
  (if (and watermark (tick/> (tick/date watermark) (tick/date (:start game-meta))))
    watermark
    (:start game-meta)))

(defn next-decklist-watermark [game-meta today]
  (let [lookback (tick/<< today (tick/new-period melee-lookback-days :days))]
    (if (tick/> lookback (tick/date (:start game-meta)))
      (str lookback)
      (:start game-meta))))

;;;; Prices ====================================================================

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

(defn group-prices
  "Returns a map from collector number to the lowest non-foil price in cents
  for a tcgcsv group."
  [group products prices]
  (let [by-product (group-by :productId prices)]
    (reduce (fn [acc product]
              (let [collector (product-collector-number group product)
                    price     (nonfoil-price (get by-product (:productId product)))]
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

(defn group-published-on
  "Returns a map from set abbreviation to its publication date. Some
  abbreviations are shared by several groups; the earliest is kept."
  [groups]
  (into {}
        (map (juxt :abbreviation #(str (tick/date (tick/date-time (:publishedOn %))))))
        (sort-by #(tick/date-time (:publishedOn %)) #(compare %2 %1) groups)))

;;;; Sync job ==================================================================

(defmachine sync-job
  [:biff.graph.fx/query sync-states-query]

  :start
  (fn [{:biff.fx/keys [now]} {:global/keys [sync-states]}]
    (when (due? (state-value sync-states "job" :sync-state/attempted-at) now)
      (let [since (state-value sync-states "swu-cards" :sync-state/watermark)]
        ;; A full card sync first deletes printings, which replaces any from
        ;; earlier data sources.
        {:_            [:biff.sqlite.fx/execute-tx
                        (cond-> [(save-state "job" :sync-state/attempted-at (str now))]
                          (nil? since) (conj {:delete-from :printing}))]
         :since        since
         :page         1
         :watermark    since
         :biff.fx/next :fetch-cards})))

  ;; Syncs printings updated since the last sync, one page at a time.
  :fetch-cards
  (fn [ctx {:keys [since page watermark]}]
    {:since        since
     :page         page
     :watermark    watermark
     :response     [:biff.fx/http (http-request ctx {:url (cards-url since page)})]
     :biff.fx/next :save-cards})

  :save-cards
  (fn [_ {:keys [since page watermark response]}]
    (let [body      (parse-json response)
          items     (:data body)
          _         (when-not (vector? items)
                      (throw (ex-info "Invalid card list" {:page page})))
          watermark (reduce latest-update
                            watermark
                            (mapv #(get-in % [:attributes :updatedAt]) items))
          more      (< page (get-in body [:meta :pagination :pageCount] 0))
          tx        (cond-> (into []
                                  (comp (keep card-printing) (mapcat card-printing-tx))
                                  items)
                      (and (not more) watermark)
                      (conj (save-state "swu-cards" :sync-state/watermark watermark)))]
      (cond-> {:since        since
               :page         (inc page)
               :watermark    watermark
               :biff.fx/next (if more :fetch-cards :fetch-prices)}
        (seq tx) (assoc :_ [:biff.sqlite.fx/execute-tx tx]))))

  :fetch-prices
  (fn [ctx _]
    {:groups       [:biff.fx/http (http-request ctx {:url (str tcgcsv "/groups")})]
     :graph        [:biff.graph.fx/query
                    [{:global/card-sets [:card-set/id]}
                     {:global/standard-printings
                      [:printing/card-id :printing/collector-number]}]]
     :biff.fx/next :fetch-group-prices})

  :fetch-group-prices
  (fn [ctx {:keys [groups graph]}]
    (let [known-sets (into #{} (map :card-set/id) (:global/card-sets graph))
          groups     (filterv #(contains? known-sets (:abbreviation %))
                              (:results (parse-json groups)))]
      {:groups       groups
       :printings    (:global/standard-printings graph)
       ;; Each group's products, then its prices.
       :responses    [:biff.fx/http
                      (into []
                            (mapcat (fn [{:keys [groupId]}]
                                      (let [root (str tcgcsv "/" groupId)]
                                        [(http-request ctx {:url (str root "/products")})
                                         (http-request ctx {:url (str root "/prices")})])))
                            groups)]
       :biff.fx/next :save-prices}))

  :save-prices
  (fn [_ {:keys [groups printings responses]}]
    (let [results (partitionv 2 (mapv (comp :results parse-json) responses))
          prices  (card-prices printings
                               (apply merge-with min {}
                                      (mapv (fn [group [products prices]]
                                              (group-prices group products prices))
                                            groups
                                            results)))]
      {:_            [:biff.sqlite.fx/execute-tx
                      (-> (mapv (fn [[set-id date]]
                                  {:update :card-set
                                   :set    {:card-set/published-on date}
                                   :where  [:= :card-set/id set-id]})
                                (group-published-on groups))
                          (conj {:update :card :set {:card/price-cents nil}})
                          (into (mapv (fn [[id cents]]
                                        {:update :card
                                         :set    {:card/price-cents cents}
                                         :where  [:= :card/id id]})
                                      prices)))]
       :biff.fx/next :fetch-meta}))

  :fetch-meta
  (fn [_ _]
    {:graph        [:biff.graph.fx/query
                    [{:global/card-sets [:card-set/id
                                         [:? :card-set/number]
                                         [:? :card-set/published-on]]}
                     {:global/metas [:meta/id :meta/is-current]}]]
     :biff.fx/next :save-meta})

  ;; Records the current meta. When it changes, the decklist watermark is
  ;; cleared so that the decklist sync replaces all tournaments.
  :save-meta
  (fn [{:biff.fx/keys [now]} {:keys [graph]}]
    (if-some [game-meta (current-meta (:global/card-sets graph) (utc-date now))]
      (let [prior (first (filterv :meta/is-current (:global/metas graph)))]
        {:_            [:biff.sqlite.fx/execute-tx
                        (conj (if (= (:meta/id prior) (:id game-meta))
                                []
                                [{:delete-from :sync-state
                                  :where       [:= :sync-state/id "melee-decklists"]}
                                 {:update :meta :set {:meta/is-current false}}])
                              (upsert :meta :meta/id
                                      {:meta/id         (:id game-meta)
                                       :meta/start-date (:start game-meta)
                                       :meta/end-date   nil
                                       :meta/set-id     (:id game-meta)
                                       :meta/is-current true}))]
         :game-meta    game-meta
         :biff.fx/next :fetch-sign-in-page})
      {:biff.fx/next :fetch-recommendations}))

  ;; Syncs decklists from ended tournaments in the current meta that were held
  ;; since the watermark. melee.gg's decklist search requires signing in.
  :fetch-sign-in-page
  (fn [ctx {:keys [game-meta]}]
    {:game-meta    game-meta
     :graph        [:biff.graph.fx/query
                    (conj sync-states-query {:global/tournaments [:tournament/id]})]
     :page         [:biff.fx/http (http-request ctx {:url (str melee "/Account/SignIn")})]
     :biff.fx/next :sign-in})

  :sign-in
  (fn [{:biff.fx/keys [now] :as ctx} {:keys [game-meta graph page]}]
    (let [token     (second (re-find sign-in-token-pattern (:body page)))
          cookies   (get-in page [:headers "set-cookie"])
          watermark (state-value (:global/sync-states graph)
                                 "melee-decklists"
                                 :sync-state/watermark)]
      (when-not token
        (throw (ex-info "Couldn't find melee.gg's sign-in form" {})))
      ;; A full sync deletes all tournaments, which replaces any from an
      ;; earlier meta or data source.
      {:game-meta    game-meta
       :today        (utc-date now)
       :watermark    watermark
       :known        (if watermark
                       (into #{} (map :tournament/id) (:global/tournaments graph))
                       #{})
       :cookies      cookies
       :response     [:biff.fx/http (sign-in-request ctx token cookies)]
       :biff.fx/next :search-decklists}))

  :search-decklists
  (fn [ctx {:keys [game-meta today watermark known cookies response]}]
    (let [body   (parse-json response)
          cookie (str (cookie-header cookies) "; "
                      (cookie-header (get-in response [:headers "set-cookie"])))
          from   (decklist-search-start game-meta watermark)]
      (when (or (not= 200 (:status response)) (:Error body))
        (throw (ex-info "Couldn't sign in to melee.gg" {:status (:status response)})))
      {:game-meta    game-meta
       :today        today
       :watermark    watermark
       :known        known
       :cookie       cookie
       :from         from
       :start        0
       :rows         []
       :response     [:biff.fx/http (decklist-search-request ctx cookie 0 from (str today))]
       :biff.fx/next :next-decklists}))

  :next-decklists
  (fn [ctx {:keys [today known cookie from start rows response] :as state}]
    (let [body (parse-json response)
          rows (into rows (:data body))
          more (< (+ start decklist-page-size) (or (:recordsFiltered body) 0))]
      (when-not (vector? (:data body))
        (throw (ex-info "Invalid decklist search response" {:start start})))
      (if more
        (-> (select-keys state [:game-meta :today :watermark :known :cookie :from])
            (assoc :start        (+ start decklist-page-size)
                   :rows         rows
                   :response     [:biff.fx/http (decklist-search-request
                                                 ctx
                                                 cookie
                                                 (+ start decklist-page-size)
                                                 from
                                                 (str today))]
                   :biff.fx/next :next-decklists))
        (let [decklists   (mapv melee-decklist
                                (filterv #(= "Ended" (:TournamentStatusDescription %)) rows))
              tournaments (new-tournaments known decklists)]
          (-> (select-keys state [:game-meta :today :watermark])
              (assoc :decklists    decklists
                     :tournaments  tournaments
                     :graph        [:biff.graph.fx/query [{:global/cards [:card/id]}]]
                     :pages        [:biff.fx/http
                                    (mapv #(http-request
                                            ctx
                                            {:url (str melee "/Tournament/View/"
                                                       (:tournament/id %))})
                                          tournaments)]
                     :biff.fx/next :save-decklists))))))

  ;; Decklists with cards that the card sync didn't find are skipped.
  :save-decklists
  (fn [_ {:keys [game-meta today watermark decklists tournaments pages graph]}]
    (let [card-ids (into #{} (map :card/id) (:global/cards graph))]
      {:_            [:biff.sqlite.fx/execute-tx
                      (-> (if watermark
                            []
                            [{:delete-from :deck-card}
                             {:delete-from :decklist}
                             {:delete-from :tournament}])
                          (into (mapv (fn [tournament page]
                                        (upsert :tournament :tournament/id
                                                (assoc tournament
                                                       :tournament/player-count
                                                       (enrolled-players (:body page)))))
                                      tournaments
                                      pages))
                          (into (comp (filter #(known-cards? card-ids %))
                                      (mapcat decklist-tx))
                                decklists)
                          (conj (save-state "melee-decklists"
                                            :sync-state/watermark
                                            (next-decklist-watermark game-meta today))))]
       :biff.fx/next :fetch-recommendations}))

  :fetch-recommendations
  (fn [_ _]
    {:graph        [:biff.graph.fx/query [{:global/front recommendations/fields}]]
     :biff.fx/next :save-recommendations})

  :save-recommendations
  (fn [ctx {:keys [graph]}]
    (let [front (:global/front graph)]
      {:_tx          [:biff.sqlite.fx/execute-tx
                      (into [{:delete-from :recommendation}]
                            (map-indexed
                             (fn [rank deck]
                               {:insert-into :recommendation
                                :values      [{:recommendation/id   (:recommendation/id deck)
                                               :recommendation/rank rank}]}))
                            front)]
       :_home        [:com.budgetswu.fx/write-home-html
                      (:body (recommendations/home-response ctx front))]
       :biff.fx/next :notify}))

  :notify
  (fn [{:com.budgetswu/keys [base-url mailing-address]} _]
    (if (and base-url mailing-address)
      {:graph        [:biff.graph.fx/query [{:global/notification-candidates [:user/id]}]]
       :biff.fx/next :enqueue-notifications}
      [:com.budgetswu.fx/log :warn
       "Skipping notification emails: BASE_URL or MAILING_ADDRESS is unset"]))

  :enqueue-notifications
  (fn [_ {:keys [graph]}]
    {:_ [:biff.background.fx/submit-jobs
         ::notifications
         (:global/notification-candidates graph)]}))

;;;; Notifications =============================================================

(defn- set-seen [user-id ids]
  {:update :user
   :set    {:user/seen-recommendations [:lift ids]}
   :where  [:= :user/id user-id]})

(defpipeline notify-user
  (fn [{:biff.background/keys [job]}]
    [:biff.graph.fx/query
     {:user/id (:user/id job)}
     [:user/id
      :user/email
      [:? :user/seen-recommendations]
      {:user/recommendations recommendations/fields}]])

  (fn [{:biff.fx/keys [random-uuid4-seq] :as ctx}
       {:user/keys [id email seen-recommendations recommendations]}]
    (let [current   (into #{} (map :recommendation/id) recommendations)
          new-decks (filterv #(not (contains? seen-recommendations
                                              (:recommendation/id %)))
                             recommendations)
          token     (first random-uuid4-seq)]
      (cond
        ;; Users from before notifications existed: start tracking without
        ;; emailing them about every current deck.
        (nil? seen-recommendations)
        {:biff.fx/return nil
         :_              [:biff.sqlite.fx/execute (set-seen id current)]}

        (empty? new-decks)
        {:biff.fx/return nil}

        ;; Record the set before sending so a failure can't cause repeats.
        :else
        {:_     [:biff.sqlite.fx/execute-tx
                 [(set-seen id current)
                  (notification/insert ctx id token)]]
         :email (notification/email ctx email recommendations new-decks token)})))

  (fn [ctx {:keys [email]}]
    {:email    email
     :response (email/send-fx ctx email)})

  (fn [_ {:keys [email response]}]
    (when-some [error (email/failure response)]
      (throw (ex-info "Couldn't send notification email"
                      {:to (:to email) :error error})))))

(def module
  {:biff.background/tasks
   [{:schedule (fn []
                 (iterate #(tick/>> % (tick/new-duration 15 :minutes))
                          (tick/now)))
     :task     #'sync-job}]

   :biff.background/queues
   {::notifications {:consumer  #'notify-user
                     :n-threads 1}}})
