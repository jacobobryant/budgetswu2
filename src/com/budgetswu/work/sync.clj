(ns com.budgetswu.work.sync
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.biffweb.fx :refer [defpipeline]]
            [com.budgetswu.lib.notification :as notification]
            [com.budgetswu.lib.ranking :as ranking]
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

(defn user-agent
  "melee.gg rejects some user agents, including ones without a URL."
  [{:com.budgetswu/keys [base-url]}]
  (str "Mozilla/5.0 (compatible; BudgetSWU/1.0"
       (when base-url (str "; +" base-url))
       ")"))

(defpipeline request
  (fn [ctx req]
    [:biff.fx/http (-> req
                       (update :method #(or % :get))
                       (assoc-in [:headers "User-Agent"] (user-agent ctx)))]))

(defn cookie-header
  "Returns a Cookie header value from Set-Cookie header values."
  [set-cookies]
  (str/join "; "
            (mapv #(first (str/split % #";"))
                  (if (string? set-cookies) [set-cookies] set-cookies))))

(def ^:private sign-in-token-pattern
  #"name=\"__RequestVerificationToken\" type=\"hidden\" value=\"([^\"]+)\"")

;; Signs in to melee.gg and returns a Cookie header value for the session.
(defpipeline melee-sign-in
  (fn [ctx]
    [:biff.fx/http {:url     (str melee "/Account/SignIn")
                    :method  :get
                    :headers {"User-Agent" (user-agent ctx)}}])

  (fn [ctx page]
    (let [token   (second (re-find sign-in-token-pattern (:body page)))
          cookies (get-in page [:headers "set-cookie"])]
      (when-not token
        (throw (ex-info "Couldn't find melee.gg's sign-in form" {})))
      {:cookies  cookies
       :response [:com.budgetswu.fx/melee-sign-in
                  {:url     (str melee "/Account/SignInPassword")
                   :token   token
                   :headers {"User-Agent"       (user-agent ctx)
                             "Cookie"           (cookie-header cookies)
                             "X-Requested-With" "XMLHttpRequest"}}]}))

  (fn [_ {:keys [cookies response]}]
    (let [body (json/parse-string (:body response) true)]
      (when (or (not= 200 (:status response)) (:Error body))
        (throw (ex-info "Couldn't sign in to melee.gg"
                        {:status (:status response)})))
      (str (cookie-header cookies) "; "
           (cookie-header (get-in response [:headers "set-cookie"]))))))

(defn- fetch-json [ctx url]
  (json/parse-string (:body (request ctx {:url url})) true))

(defn- query-string [params]
  (let [encode #(URLEncoder/encode (str %) "UTF-8")]
    (str/join "&"
              (mapv (fn [[k v]]
                      (str (encode (if (keyword? k) (name k) k)) "=" (encode v)))
                    params))))

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

;;;; Cards =====================================================================

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
        id*       (card-id {:name title :subtitle subtitle})]
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

(defn- sync-cards
  "Syncs printings updated since the last sync. A full sync first deletes
  printings, which replaces any from earlier data sources."
  [ctx]
  (let [since (state-value ctx "swu-cards" :sync-state/watermark)]
    (when-not since
      (execute ctx {:delete-from :printing}))
    (loop [page      1
           watermark since]
      (let [response  (fetch-json ctx
                                  (url swu-cards ""
                                       (cond-> [["locale" "en"]
                                                ["sort[0]" "updatedAt:asc"]
                                                ["sort[1]" "id:asc"]
                                                ["pagination[page]" page]
                                                ["pagination[pageSize]" 250]]
                                         since (conj ["filters[updatedAt][$gt]" since]))))
            items     (:data response)
            watermark (reduce (fn [acc item]
                                (let [updated (get-in item [:attributes :updatedAt])]
                                  (if (or (nil? acc) (pos? (compare updated acc)))
                                    updated
                                    acc)))
                              watermark
                              items)]
        (when-not (vector? items)
          (throw (ex-info "Invalid card list" {:page page})))
        (doseq [printing (keep card-printing items)]
          (execute-tx ctx (card-printing-tx printing)))
        (if (< page (get-in response [:meta :pagination :pageCount] 0))
          (recur (inc page) watermark)
          (when watermark
            (save-state ctx "swu-cards" :sync-state/watermark watermark)))))))

;;;; Meta ======================================================================

(defn current-meta
  "Returns the current meta, which starts when the latest numbered set is
  released."
  [card-sets today]
  (when-let [card-set (->> card-sets
                           (filterv #(and (:card-set/number %)
                                          (:card-set/published-on %)
                                          (<= (compare (:card-set/published-on %)
                                                       (str today))
                                              0)))
                           (sort-by (juxt (comp - :card-set/number)
                                          :card-set/published-on
                                          :card-set/id))
                           first)]
    {:id    (:card-set/id card-set)
     :start (:card-set/published-on card-set)}))

(defn- reset-current-meta
  "Records the current meta. When it changes, the decklist watermark is cleared
  so that the next decklist sync replaces all tournaments."
  [ctx meta]
  (let [prior (first (filterv :meta/is-current
                              (:global/metas
                               (graph-query ctx
                                            [{:global/metas
                                              [:meta/id
                                               :meta/is-current]}]))))]
    (when (not= (:meta/id prior) (:id meta))
      (execute-tx ctx [{:delete-from :sync-state
                        :where       [:= :sync-state/id "melee-decklists"]}
                       {:update :meta :set {:meta/is-current false}}]))
    (execute ctx
             (upsert :meta :meta/id
                     {:meta/id         (:id meta)
                      :meta/start-date (:start meta)
                      :meta/end-date   nil
                      :meta/set-id     (:id meta)
                      :meta/is-current true}))))

;;;; Decklists =================================================================

(def ^:private search-columns
  ["DecklistName" "Game" "FormatId" "FormatName" "OwnerDisplayName"
   "TournamentName" "SortDate" "TeamRank" "TeamMatchWins" "OrganizationName"
   "Records" "Archetypes" "TournamentTags" "LeaderName" "SecondaryName"])

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
                           (card-id {:name (:n card) :subtitle (:s card)})))
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
                                         (card-id {:name n :subtitle s})
                                         (fnil + 0)
                                         q))
                               {}
                               cards))}))

(defn enrolled-players
  "Returns the number of players from a melee.gg tournament page."
  [html]
  (some->> html
           (re-find #"(\d+)(?: of \d+)? Enrolled Players?")
           second
           parse-long))

(defn- search-decklists [ctx cookies from to]
  (let [page-size 500]
    (loop [start 0
           rows  []]
      (let [response (json/parse-string
                      (:body (request ctx
                                      {:url         (str melee "/Decklist/SearchDecklists")
                                       :method      :post
                                       :headers     {"Cookie"           cookies
                                                     "X-Requested-With" "XMLHttpRequest"}
                                       :form-params (decklist-search-params
                                                     start page-size from to)}))
                      true)
            rows     (into rows (:data response))]
        (when-not (vector? (:data response))
          (throw (ex-info "Invalid decklist search response" {:start start})))
        (if (< (+ start page-size) (or (:recordsFiltered response) 0))
          (recur (+ start page-size) rows)
          rows)))))

(defn- delete-tournaments [ctx]
  (execute-tx ctx [{:delete-from :deck-card}
                   {:delete-from :decklist}
                   {:delete-from :tournament}]))

(defn decklist-search-start
  "Returns the first date to search for decklists: the meta's start for a full
  sync, otherwise the watermark."
  [meta watermark]
  (if (and watermark (pos? (compare watermark (:start meta))))
    watermark
    (:start meta)))

(defn next-decklist-watermark [meta today]
  (let [lookback (str (tick/<< today (tick/new-period melee-lookback-days :days)))]
    (if (pos? (compare lookback (:start meta)))
      lookback
      (:start meta))))

(defn- sync-decklists
  "Syncs decklists from ended tournaments in the current meta that were held
  since the watermark. A full sync first deletes all tournaments, which replaces
  any from an earlier meta or data source."
  [ctx meta]
  (let [watermark (state-value ctx "melee-decklists" :sync-state/watermark)
        today     (tick/today)
        rows      (search-decklists ctx
                                    (melee-sign-in ctx)
                                    (decklist-search-start meta watermark)
                                    (str today))
        _         (when-not watermark
                    (delete-tournaments ctx))
        known     (atom (into #{}
                              (map :tournament/id)
                              (:global/tournaments
                               (graph-query ctx
                                            [{:global/tournaments
                                              [:tournament/id
                                               :tournament/held-at]}]))))]
    (doseq [{:keys [tournament decklist cards]}
            (mapv melee-decklist
                  (filterv #(= "Ended" (:TournamentStatusDescription %)) rows))]
      (when-not (contains? @known (:tournament/id tournament))
        (let [page (request ctx {:url (str melee "/Tournament/View/"
                                           (:tournament/id tournament))})]
          (execute ctx (upsert :tournament :tournament/id
                               (assoc tournament
                                      :tournament/player-count
                                      (enrolled-players (:body page)))))
          (swap! known conj (:tournament/id tournament))))
      (execute-tx ctx
                  (into [(upsert :decklist :decklist/id decklist)
                         {:delete-from :deck-card
                          :where       [:= :deck-card/decklist-id (:decklist/id decklist)]}]
                        (mapv (fn [card]
                                {:insert-into :deck-card :values [card]})
                              cards))))
    (save-state ctx "melee-decklists" :sync-state/watermark
                (next-decklist-watermark meta today))))

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

(defn- group-prices
  "Returns a map from collector number to the lowest non-foil price in cents
  for a tcgcsv group."
  [ctx group]
  (let [root       (str tcgcsv "/" (:groupId group))
        products   (:results (fetch-json ctx (str root "/products")))
        prices     (:results (fetch-json ctx (str root "/prices")))
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
  (let [groups (:results (fetch-json ctx (str tcgcsv "/groups")))

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

;;;; Notifications =============================================================

(defn- set-seen [user-id ids]
  {:update :user
   :set    {:user/seen-recommendations [:lift ids]}
   :where  [:= :user/id user-id]})

(defpipeline notify-user
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
        {:_ [:biff.sqlite.fx/execute (set-seen id current)]}

        (empty? new-decks)
        {}

        ;; Record the set before sending so a failure can't cause repeats.
        :else
        {:_     [:biff.sqlite.fx/execute-tx
                 [(set-seen id current)
                  (notification/insert ctx id token)]]
         :email (notification/email ctx email recommendations new-decks token)})))

  (fn [_ {:keys [email]}]
    (when email
      [:com.budgetswu.fx/send-email email])))

(defn- notify-users [{:com.budgetswu/keys [base-url mailing-address] :as ctx}]
  (if-not (and base-url mailing-address)
    (log/warn "Skipping notification emails: BASE_URL or MAILING_ADDRESS is unset")
    (doseq [user (:global/notification-candidates
                  (graph-query ctx
                               [{:global/notification-candidates
                                 [:user/id
                                  :user/email
                                  [:? :user/seen-recommendations]
                                  {:user/recommendations ranking/fields}]}]))]
      (try
        (notify-user ctx user)
        (catch Exception e
          (log/error e "Failed to notify user" (:user/id user)))))))

(defn sync-now [ctx]
  (sync-cards ctx)
  (sync-prices ctx)
  (when-let [meta (current-meta (:global/card-sets
                                 (graph-query ctx
                                              [{:global/card-sets
                                                [:card-set/id
                                                 [:? :card-set/number]
                                                 [:? :card-set/published-on]]}]))
                                (tick/today))]
    (reset-current-meta ctx meta)
    (sync-decklists ctx meta))
  (let [front (update-recommendations ctx)
        html  (:body (recommendations/home-response ctx front))]
    (write-home ctx html)
    (notify-users ctx)
    (count front)))

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
