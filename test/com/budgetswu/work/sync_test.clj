(ns com.budgetswu.work.sync-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [com.budgetswu.lib.email :as email]
            [com.budgetswu.lib.notification-test :refer [deck]]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.work.sync :as sync]
            [tick.core :as tick]))

(deftest http-request
  (is (= {:url     "https://example.com/x"
          :method  :get
          :headers {"User-Agent"
                    "Mozilla/5.0 (compatible; BudgetSWU/1.0; +https://example.com/)"}}
         (sync/http-request {:com.budgetswu/base-url "https://example.com/"}
                            {:url "https://example.com/x"}))))

(deftest sync-states
  (let [states [{:sync-state/id "job" :sync-state/attempted-at "2026-10-12T00:00:00Z"}
                {:sync-state/id "swu-cards" :sync-state/watermark "w"}]]
    (is (= "w" (sync/state-value states "swu-cards" :sync-state/watermark)))
    (is (nil? (sync/state-value states "melee-decklists" :sync-state/watermark))))
  (is (sync/due? nil (tick/instant "2026-10-12T00:00:00Z")))
  (is (not (sync/due? "2026-10-12T00:00:00Z" (tick/instant "2026-10-12T11:59:00Z"))))
  (is (sync/due? "2026-10-12T00:00:00Z" (tick/instant "2026-10-12T12:00:00Z")))
  (is (= (tick/date "2026-10-12") (sync/utc-date (tick/instant "2026-10-12T23:00:00Z")))))

(deftest latest-update
  (is (= "2026-10-12T10:00:00.000Z"
         (reduce sync/latest-update
                 nil
                 ["2026-10-12T09:00:00.000Z"
                  "2026-10-12T10:00:00.000Z"
                  "2026-10-12T08:00:00.000Z"]))))

(deftest tcgcsv-pricing
  (let [price     (var sync/nonfoil-price)
        collector (var sync/product-collector-number)]
    (is (= 125
           (price [{:subTypeName "Foil" :lowPrice 0.25}
                   {:subTypeName "Normal" :lowPrice 1.25}])))
    (is (nil? (price [{:subTypeName "Foil" :lowPrice 0.25}])))
    (is (= "SOR_019"
           (collector {:abbreviation "SOR"}
                      {:extendedData [{:name  "Number"
                                       :value "019/252"}]})))))

(deftest card-identity
  (is (= "Luke Skywalker, Faithful Friend"
         (sync/card-id {:title "Luke Skywalker" :subtitle "Faithful Friend"})))
  (is (= "Battlefield Marine"
         (sync/card-id {:title "Battlefield Marine" :subtitle nil})))
  (is (= 9 (sync/set-number "09010002")))
  (is (nil? (sync/set-number "p2601"))))

(deftest card-prices
  (is (= {"Luke" 100 "Han" 300}
         (sync/card-prices
          [{:printing/card-id "Luke" :printing/collector-number "SOR_005"}
           {:printing/card-id "Luke" :printing/collector-number "JTL_010"}
           {:printing/card-id "Han" :printing/collector-number "SOR_006"}
           {:printing/card-id "Leia" :printing/collector-number "SOR_007"}]
          {"SOR_005" 250 "JTL_010" 100 "SOR_006" 300}))))

(defn- printing [& {:as attributes}]
  {:id         12
   :attributes (merge {:title        "Luke Skywalker"
                       :subtitle     "Faithful Friend"
                       :cardNumber   5
                       :serialCode   "0101005"
                       :cardUid      "2579145458"
                       :type         {:data {:attributes {:name "Leader"}}}
                       :expansion    {:data {:attributes {:code "SOR"}}}
                       :variantTypes {:data [{:attributes {:name "Standard"}}]}
                       :aspects      {:data [{:attributes {:name "Vigilance"}}
                                             {:attributes {:name "Heroism"}}]}}
                      attributes)})

(deftest card-printing
  (is (= {:card     {:card/id "Luke Skywalker, Faithful Friend" :card/type "Leader"}
          :card-set {:card-set/id "SOR" :card-set/number 1}
          :printing {:printing/id               "12"
                     :printing/card-id          "Luke Skywalker, Faithful Friend"
                     :printing/card-set-id      "SOR"
                     :printing/collector-number "SOR_005"
                     :printing/variant          "Standard"
                     :printing/external-uid     "2579145458"}
          :aspects  #{"Vigilance" "Heroism"}}
         (sync/card-printing (printing))))
  (testing "hyperspace promos from sets 1-3 have their own set"
    (is (= ["SORP" "SORP_001"]
           ((juxt :printing/card-set-id :printing/collector-number)
            (:printing (sync/card-printing (printing :cardNumber 1
                                                     :serialCode "0102001")))))))
  (testing "main-set hyperspace printings don't"
    (is (= "SOR_359"
           (get-in (sync/card-printing (printing :cardNumber 359
                                                 :serialCode "0102359"))
                   [:printing :printing/collector-number]))))
  (testing "token numbers are prefixed with T"
    (is (= "LOF_T004"
           (get-in (sync/card-printing
                    (printing :cardNumber 4
                              :serialCode "05020t04"
                              :expansion {:data {:attributes {:code "LOF"}}}
                              :type {:data {:attributes {:name "Token Upgrade"}}}))
                   [:printing :printing/collector-number]))))
  (testing "printings without a set or number are skipped"
    (is (nil? (sync/card-printing (printing :expansion {:data nil}))))
    (is (nil? (sync/card-printing (printing :cardNumber nil))))))

(deftest current-meta
  (let [sets [{:card-set/id "ASH" :card-set/number 8 :card-set/published-on "2026-07-17"}
              {:card-set/id "HMW" :card-set/number 9 :card-set/published-on "2026-10-09"}
              {:card-set/id "HMWP" :card-set/number 9 :card-set/published-on "2026-10-09"}
              {:card-set/id "IC27" :card-set/number 10 :card-set/published-on "2026-11-20"}
              {:card-set/id "P26" :card-set/published-on "2026-10-10"}]]
    (is (= {:id "HMW" :start "2026-10-09"}
           (sync/current-meta sets (tick/date "2026-10-12"))))
    (is (= {:id "ASH" :start "2026-07-17"}
           (sync/current-meta sets (tick/date "2026-10-08"))))
    (is (nil? (sync/current-meta [] (tick/date "2026-10-12"))))))

(deftest decklist-watermarks
  (let [game-meta {:id "HMW" :start "2026-10-09"}]
    (is (= "2026-10-09" (sync/decklist-search-start game-meta nil)))
    (is (= "2026-10-09" (sync/decklist-search-start game-meta "2026-07-01")))
    (is (= "2026-10-20" (sync/decklist-search-start game-meta "2026-10-20")))
    (is (= "2026-10-09" (sync/next-decklist-watermark game-meta (tick/date "2026-10-12"))))
    (is (= "2026-10-23" (sync/next-decklist-watermark game-meta (tick/date "2026-10-30"))))))

(deftest melee-decklist
  (is (= {:tournament {:tournament/id      "457640"
                       :tournament/name    "PAX Aus Open 2026"
                       :tournament/held-at "2026-10-10T00:00:00Z"
                       :tournament/format  "Premier"}
          :decklist   {:decklist/id            "guid"
                       :decklist/tournament-id "457640"
                       :decklist/player-name   "Gunn3d"
                       :decklist/leader-id     "Leia Organa, Someone Who Loves You"
                       :decklist/base-id       "Daimyo's Palace"
                       :decklist/format        "Premier"
                       :decklist/placement     39}
          :cards      [{:deck-card/id          "guid:Leia Organa, Someone Who Loves You"
                        :deck-card/decklist-id "guid"
                        :deck-card/card-id     "Leia Organa, Someone Who Loves You"
                        :deck-card/quantity    1}
                       {:deck-card/id          "guid:Daimyo's Palace"
                        :deck-card/decklist-id "guid"
                        :deck-card/card-id     "Daimyo's Palace"
                        :deck-card/quantity    1}
                       {:deck-card/id          "guid:Common Cause"
                        :deck-card/decklist-id "guid"
                        :deck-card/card-id     "Common Cause"
                        :deck-card/quantity    3}]}
         (sync/melee-decklist
          {:Guid                "guid"
           :TournamentId        457640
           :TournamentName      "PAX Aus Open 2026"
           :TournamentStartDate "2026-10-10T00:00:00Z"
           :OwnerDisplayName    "Gunn3d"
           :TeamRank            39
           :Records             [{:n "Leia Organa" :s "Someone Who Loves You" :q 1 :c 6}
                                 {:n "Daimyo's Palace" :s nil :q 1 :c 7}
                                 {:n "Common Cause" :s nil :q 2 :c 0}
                                 {:n "Common Cause" :s nil :q 1 :c 0}
                                 {:n "Zeb Orellios" :s "Spectre Four" :q 2 :c 99}]}))))

(deftest melee-pages
  (is (= 47 (sync/enrolled-players
             "<i class=\"fa fa-user mr-1\"></i>47 of 96 Enrolled Players\n")))
  (is (= 12 (sync/enrolled-players "12 Enrolled Players")))
  (is (nil? (sync/enrolled-players "<html></html>")))
  (is (= "a=1; b=2" (sync/cookie-header ["a=1; path=/" "b=2; HttpOnly"])))
  (let [params (sync/decklist-search-params 500 500 "2026-10-09" "2026-10-12")]
    (is (= "500" (get params "start")))
    (is (= "SortDate" (get params "columns[6][data]")))
    (is (= "2026-10-09|2026-10-12" (get params "columns[6][search][value]")))
    (is (= "StarWarsUnlimited" (get params "columns[1][search][value]")))
    (is (= sync/melee-premier-format-id (get params "columns[2][search][value]")))))

(deftest prices-helpers
  (is (= {"SOR_005" 100}
         (sync/group-prices {:abbreviation "SOR"}
                            [{:productId 1 :extendedData [{:name "Number" :value "005/252"}]}
                             {:productId 2 :extendedData [{:name "Number" :value "005/252"}]}]
                            [{:productId 1 :subTypeName "Normal" :lowPrice 1.5}
                             {:productId 2 :subTypeName "Normal" :lowPrice 1.0}])))
  (testing "shared abbreviations keep the earliest publication date"
    (is (= {"SOR" "2024-03-08" "JTL" "2025-03-14"}
           (sync/group-published-on [{:abbreviation "SOR" :publishedOn "2024-05-01T00:00:00"}
                                     {:abbreviation "SOR" :publishedOn "2024-03-08T00:00:00"}
                                     {:abbreviation "JTL" :publishedOn "2025-03-14T00:00:00"}])))))

(deftest new-tournaments
  (let [decklist (fn [id] {:tournament {:tournament/id id}})]
    (is (= [{:tournament/id "b"} {:tournament/id "c"}]
           (sync/new-tournaments #{"a"} (mapv decklist ["a" "b" "c" "b"]))))))

(def ctx
  {:com.budgetswu/base-url        "https://example.com/"
   :com.budgetswu/mailing-address "1 Main St"})

(def now (tick/instant "2026-10-12T12:00:00Z"))

(def states (sync/sync-job))

(defn- json-response [body & {:as extra}]
  (merge {:status 200 :body (json/generate-string body)} extra))

(deftest sync-job-start
  (let [start (:start states)]
    (testing "nothing happens until the sync is due"
      (is (nil? (start {:biff.fx/now now}
                       {:global/sync-states [{:sync-state/id           "job"
                                              :sync-state/attempted-at "2026-10-12T06:00:00Z"}]}))))
    (testing "the first card sync deletes printings"
      (is (= {:_            [:biff.sqlite.fx/execute-tx
                             [{:insert-into   :sync-state
                               :values        [{:sync-state/id           "job"
                                                :sync-state/attempted-at (str now)}]
                               :on-conflict   [:sync-state/id]
                               :do-update-set [:sync-state/id :sync-state/attempted-at]}
                              {:delete-from :printing}]]
              :since        nil
              :page         1
              :watermark    nil
              :biff.fx/next :fetch-cards}
             (start {:biff.fx/now now} {:global/sync-states []}))))
    (testing "manual syncs run unless a sync started after the request"
      (let [states {:global/sync-states [{:sync-state/id           "job"
                                          :sync-state/attempted-at "2026-10-12T06:00:00Z"}]}]
        (is (= :fetch-cards
               (:biff.fx/next (start {:biff.fx/now now} states
                                     {:requested-at (tick/instant "2026-10-12T07:00:00Z")}))))
        (is (nil? (start {:biff.fx/now now} states
                         {:requested-at (tick/instant "2026-10-12T05:00:00Z")})))))
    (testing "later card syncs start from the watermark"
      (let [result (start {:biff.fx/now now}
                          {:global/sync-states [{:sync-state/id        "swu-cards"
                                                 :sync-state/watermark "w"}]})]
        (is (= ["w" "w"] ((juxt :since :watermark) result)))
        (is (= 1 (count (second (:_ result)))))))))

(deftest sync-job-cards
  (let [{:keys [fetch-cards save-cards]} states

        item     {:id         12
                  :attributes {:title        "Luke Skywalker"
                               :cardNumber   5
                               :serialCode   "0101005"
                               :updatedAt    "2026-10-12T10:00:00.000Z"
                               :type         {:data {:attributes {:name "Leader"}}}
                               :expansion    {:data {:attributes {:code "SOR"}}}
                               :variantTypes {:data [{:attributes {:name "Standard"}}]}
                               :aspects      {:data []}}}
        response (fn [page-count]
                   (json-response {:data [item] :meta {:pagination {:pageCount page-count}}}))]
    (let [result (fetch-cards ctx {:since "w" :page 2 :watermark "w" :response :stale})]
      (is (= [:biff.fx/http :fetch-cards :save-cards]
             [(first (:response result)) :fetch-cards (:biff.fx/next result)]))
      (is (re-find #"pagination%5Bpage%5D=2.*filters%5BupdatedAt%5D%5B%24gt%5D=w"
                   (get-in result [:response 1 :url]))))
    (testing "more pages are fetched"
      (let [result (save-cards {} {:since nil :page 1 :watermark nil :response (response 2)})]
        (is (= [:fetch-cards 2 "2026-10-12T10:00:00.000Z"]
               ((juxt :biff.fx/next :page :watermark) result)))
        (is (= 4 (count (get-in result [:_ 1]))))))
    (testing "the watermark is saved after the last page"
      (let [result (save-cards {} {:since nil :page 2 :watermark nil :response (response 2)})]
        (is (= :fetch-prices (:biff.fx/next result)))
        (is (= {:sync-state/id "swu-cards" :sync-state/watermark "2026-10-12T10:00:00.000Z"}
               (get-in (peek (get-in result [:_ 1])) [:values 0])))))
    (testing "empty pages have no transaction"
      (is (nil? (:_ (save-cards {} {:page     1
                                    :response (json-response {:data []})})))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (save-cards {} {:page 1 :response (json-response {:error true})})))))

(deftest sync-job-prices
  (let [{:keys [fetch-prices fetch-group-prices save-prices]} states

        group {:groupId 7 :abbreviation "SOR" :publishedOn "2024-03-08T00:00:00"}]
    (is (= :fetch-group-prices (:biff.fx/next (fetch-prices ctx {}))))
    (let [result (fetch-group-prices
                  ctx
                  {:groups (json-response {:results [group {:abbreviation "XYZ"}]})
                   :graph  {:global/card-sets          [{:card-set/id "SOR"}]
                            :global/standard-printings [:printing]}})]
      (is (= [group] (:groups result)))
      (is (= [:printing] (:printings result)))
      (is (= ["https://tcgcsv.com/tcgplayer/79/7/products"
              "https://tcgcsv.com/tcgplayer/79/7/prices"]
             (mapv :url (second (:responses result))))))
    (is (= {:_            [:biff.sqlite.fx/execute-tx
                           [{:update :card-set
                             :set    {:card-set/published-on "2024-03-08"}
                             :where  [:= :card-set/id "SOR"]}
                            {:update :card :set {:card/price-cents nil}}
                            {:update :card
                             :set    {:card/price-cents 125}
                             :where  [:= :card/id "Luke"]}]]
            :biff.fx/next :fetch-meta}
           (save-prices
            {}
            {:groups    [group]
             :printings [{:printing/card-id "Luke" :printing/collector-number "SOR_005"}]
             :responses [(json-response {:results [{:productId    1
                                                    :extendedData [{:name  "Number"
                                                                    :value "005/252"}]}]})
                         (json-response {:results [{:productId   1
                                                    :subTypeName "Normal"
                                                    :lowPrice    1.25}]})]})))))

(deftest sync-job-meta
  (let [{:keys [fetch-meta save-meta]} states

        sets     [{:card-set/id "HMW" :card-set/number 9 :card-set/published-on "2026-10-09"}]
        meta-row (fn [id]
                   {:meta/id         id
                    :meta/start-date "2026-10-09"
                    :meta/end-date   nil
                    :meta/set-id     id
                    :meta/is-current true})]
    (is (= :save-meta (:biff.fx/next (fetch-meta {} {}))))
    (testing "an unchanged meta keeps the decklist watermark"
      (is (= {:_            [:biff.sqlite.fx/execute-tx
                             [{:insert-into   :meta
                               :values        [(meta-row "HMW")]
                               :on-conflict   [:meta/id]
                               :do-update-set (vec (keys (meta-row "HMW")))}]]
              :game-meta    {:id "HMW" :start "2026-10-09"}
              :biff.fx/next :fetch-sign-in-page}
             (save-meta {:biff.fx/now now}
                        {:graph {:global/card-sets sets
                                 :global/metas     [{:meta/id "HMW" :meta/is-current true}]}}))))
    (testing "a new meta clears the decklist watermark"
      (is (= [{:delete-from :sync-state :where [:= :sync-state/id "melee-decklists"]}
              {:update :meta :set {:meta/is-current false}}]
             (subvec (get-in (save-meta {:biff.fx/now now}
                                        {:graph {:global/card-sets sets
                                                 :global/metas     [{:meta/id         "ASH"
                                                                     :meta/is-current true}]}})
                             [:_ 1])
                     0 2))))
    (testing "without a meta, decklists aren't synced"
      (is (= {:biff.fx/next :fetch-recommendations}
             (save-meta {:biff.fx/now now} {:graph {:global/card-sets []}}))))))

(def meta* {:id "HMW" :start "2026-10-09"})

(deftest sync-job-sign-in
  (let [{:keys [fetch-sign-in-page sign-in search-decklists]} states

        page   {:body    (str "<input name=\"__RequestVerificationToken\""
                              " type=\"hidden\" value=\"tok\" />")
                :headers {"set-cookie" "a=1; path=/; HttpOnly"}}
        graph  {:global/sync-states [{:sync-state/id        "melee-decklists"
                                      :sync-state/watermark "2026-10-10"}]
                :global/tournaments [{:tournament/id "t1"}]}
        ctx*   (assoc ctx
                      :biff.fx/now                  now
                      :com.budgetswu/melee-email    "a@example.com"
                      :com.budgetswu/melee-password (delay "p w"))
        signed (sign-in ctx* {:game-meta meta* :graph graph :page page})]
    (is (= "https://melee.gg/Account/SignIn"
           (get-in (fetch-sign-in-page ctx {:game-meta meta*}) [:page 1 :url])))
    (is (= :biff.fx/http (first (:response signed))))
    (is (= {:url     "https://melee.gg/Account/SignInPassword"
            :method  :post
            :headers {"Content-Type"     "application/x-www-form-urlencoded"
                      "Cookie"           "a=1"
                      "X-Requested-With" "XMLHttpRequest"
                      "User-Agent"       (sync/user-agent ctx)}}
           (dissoc (second (:response signed)) :body)))
    (is (= (str "__RequestVerificationToken=tok&ReturnUrl=%2F"
                "&Email=a%40example.com&Password=p+w")
           (String. ^bytes (:body (second (:response signed))) "UTF-8")))
    (testing "missing credentials are rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sync/sign-in-request ctx "tok" "a=1"))))
    (is (= ["2026-10-10" #{"t1"} (tick/date "2026-10-12")]
           ((juxt :watermark :known :today) signed)))
    (testing "a full sync treats every tournament as new"
      (is (= #{} (:known (sign-in ctx*
                                  {:game-meta meta*
                                   :graph     (dissoc graph :global/sync-states)
                                   :page      page})))))
    (testing "a missing sign-in form is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sign-in (assoc ctx :biff.fx/now now) {:graph graph :page {:body ""}}))))
    (testing "the session cookie is added to the sign-in page's cookies"
      (let [result (search-decklists ctx (assoc signed :response
                                                {:status  200
                                                 :body    "{\"RedirectUrl\":\"/\"}"
                                                 :headers {"set-cookie" ["b=2; path=/" "c=3"]}}))]
        (is (= "a=1; b=2; c=3" (:cookie result)))
        (is (= ["2026-10-10" 0 []] ((juxt :from :start :rows) result)))
        (is (= "2026-10-10|2026-10-12"
               (get-in result [:response 1 :form-params "columns[6][search][value]"])))))
    (testing "failed sign-ins are rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (search-decklists ctx (assoc signed :response {:status 200
                                                                  :body   "{\"Error\":true}"})))))))

(defn- search-row [guid tournament-id status]
  {:Guid                        guid
   :TournamentId                tournament-id
   :TournamentName              "Showdown"
   :TournamentStartDate         "2026-10-10T00:00:00Z"
   :TournamentStatusDescription status
   :TeamRank                    1
   :Records                     [{:n "Common Cause" :q 3 :c 0}]})

(deftest sync-job-decklists
  (let [{:keys [next-decklists save-decklists]} states

        state {:game-meta meta*
               :today     (tick/date "2026-10-12")
               :watermark "2026-10-10"
               :known     #{"1"}
               :cookie    "a=1"
               :from      "2026-10-10"
               :start     0
               :rows      []}]
    (testing "more search pages are fetched"
      (let [rows   [(search-row "a" 1 "Ended")]
            result (next-decklists ctx (assoc state :response
                                              (json-response {:data rows :recordsFiltered 600})))]
        (is (= [:next-decklists 500 1] ((juxt :biff.fx/next :start (comp count :rows)) result)))
        (is (= "500" (get-in result [:response 1 :form-params "start"])))))
    (testing "new tournaments' pages are fetched for ended tournaments"
      (let [rows   [(search-row "a" 1 "Ended")
                    (search-row "b" 2 "Ended")
                    (search-row "c" 2 "Ended")
                    (search-row "d" 3 "InProgress")]
            result (next-decklists ctx (assoc state :response (json-response {:data rows})))]
        (is (= :save-decklists (:biff.fx/next result)))
        (is (= ["a" "b" "c"] (mapv (comp :decklist/id :decklist) (:decklists result))))
        (is (= ["2"] (mapv :tournament/id (:tournaments result))))
        (is (= ["https://melee.gg/Tournament/View/2"]
               (mapv :url (second (:pages result)))))
        (is (= [:biff.graph.fx/query [{:global/cards [:card/id]}]] (:graph result)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (next-decklists ctx (assoc state :response (json-response {})))))
    (let [decklist (sync/melee-decklist (search-row "b" 2 "Ended"))
          save     #(get-in (save-decklists {} (merge state
                                                      {:decklists   [decklist]
                                                       :tournaments [(:tournament decklist)]
                                                       :pages       [{:body "12 Enrolled Players"}]
                                                       :graph       {:global/cards
                                                                     [{:card/id "Common Cause"}]}}
                                                      %))
                            [:_ 1])]
      (testing "decklists are saved with their tournaments"
        (let [tx (save {})]
          (is (= 12 (get-in (first tx) [:values 0 :tournament/player-count])))
          (is (= [:decklist :deck-card :deck-card] (mapv #(or (:insert-into %) (:delete-from %))
                                                         (subvec tx 1 4))))
          (is (= {:sync-state/id "melee-decklists" :sync-state/watermark "2026-10-09"}
                 (get-in (peek tx) [:values 0])))))
      (testing "decklists with unknown cards are skipped"
        (let [tx (save {:graph {:global/cards []}})]
          (is (= [:tournament :sync-state] (mapv :insert-into tx)))))
      (testing "a full sync replaces all tournaments"
        (is (= [{:delete-from :deck-card} {:delete-from :decklist} {:delete-from :tournament}]
               (subvec (save {:watermark nil}) 0 3)))))))

(deftest known-cards
  (let [decklist {:decklist {:decklist/leader-id "Luke" :decklist/base-id nil}
                  :cards    [{:deck-card/card-id "Wing Leader"}]}]
    (is (sync/known-cards? #{"Luke" "Wing Leader"} decklist))
    (is (not (sync/known-cards? #{"Wing Leader"} decklist)))
    (is (not (sync/known-cards? #{"Luke"} decklist)))))

(deftest sync-job-recommendations
  (let [{:keys [fetch-recommendations save-recommendations notify enqueue-notifications]} states]
    (is (= [:biff.graph.fx/query [{:global/front recommendations/fields}]]
           (:graph (fetch-recommendations ctx {}))))
    (let [result (save-recommendations ctx {:graph {:global/front [(deck "a" "Luke")
                                                                   (deck "b" "Leia")]}})]
      (is (= [{:delete-from :recommendation}
              {:insert-into :recommendation
               :values      [{:recommendation/id "a" :recommendation/rank 0}]}
              {:insert-into :recommendation
               :values      [{:recommendation/id "b" :recommendation/rank 1}]}]
             (get-in result [:_tx 1])))
      (is (= :com.budgetswu.fx/write-home-html (first (:_home result))))
      (is (re-find #"Luke" (second (:_home result))))
      (is (= :notify (:biff.fx/next result))))
    (testing "notifications need a base URL and mailing address"
      (is (= :enqueue-notifications (:biff.fx/next (notify ctx {}))))
      (let [result (notify (dissoc ctx :com.budgetswu/base-url) {})]
        (is (= [:com.budgetswu.fx/log :warn] (subvec (:_ result) 0 2)))
        (is (= :complete (:biff.fx/next result)))))
    (is (= {:_            [:biff.background.fx/submit-jobs
                           ::sync/notifications
                           [{:user/id 1}]]
            :biff.fx/next :complete}
           (enqueue-notifications ctx {:graph {:global/notification-candidates
                                               [{:user/id 1}]}})))))

(deftest sync-job-complete
  (is (= [:biff.sqlite.fx/execute-tx
          [{:insert-into   :sync-state
            :values        [{:sync-state/id "job" :sync-state/completed-at (str now)}]
            :on-conflict   [:sync-state/id]
            :do-update-set [:sync-state/id :sync-state/completed-at]}]]
         ((:complete states) {:biff.fx/now now} {}))))

(deftest run-sync
  (testing "failures are logged instead of thrown"
    (let [logged (atom nil)]
      (with-redefs [sync/sync-job (fn [& _] (throw (AssertionError. "boom")))
                    log/log*      (fn [_ level t _]
                                    (reset! logged [level (ex-message t)]))]
        (sync/run-sync {:biff.background/job {}})
        (is (= [:error "boom"] @logged)))))
  (is (= [:biff.background.fx/submit-jobs ::sync/sync [{:requested-at now}]]
         (sync/request-sync {:requested-at now}))))

(def token #uuid "00000000-0000-0000-0000-000000000009")

(deftest notify-user-states
  (let [[query decide send-email check] (sync/notify-user)

        uid  #uuid "00000000-0000-0000-0000-000000000001"
        nid  #uuid "00000000-0000-0000-0000-000000000002"
        now  #inst "2026-10-12T00:00:00Z"
        ctx  (assoc ctx
                    :biff.fx/now now
                    :biff.fx/random-uuid7-seq [nid]
                    :biff.fx/random-uuid4-seq [token])
        user {:user/id              uid
              :user/email           "a@example.com"
              :user/recommendations [(deck "a" "Luke") (deck "b" "Leia")]}
        seen (fn [ids]
               {:update :user
                :set    {:user/seen-recommendations [:lift ids]}
                :where  [:= :user/id uid]})]
    (is (= [:biff.graph.fx/query {:user/id uid}]
           (subvec (query {:biff.background/job {:user/id uid}}) 0 2)))
    (testing "users without a stored set start tracking without an email"
      (is (= {:biff.fx/return nil
              :_              [:biff.sqlite.fx/execute (seen #{"a" "b"})]}
             (decide ctx user))))
    (testing "removed decks alone don't trigger an email"
      (is (= {:biff.fx/return nil}
             (decide ctx (assoc user :user/seen-recommendations #{"a" "b" "c"})))))
    (testing "new decks are recorded before the email is sent"
      (let [result (decide ctx (assoc user :user/seen-recommendations #{"a"}))]
        (is (= [:biff.sqlite.fx/execute-tx
                [(seen #{"a" "b"})
                 {:insert-into :notification
                  :values      [{:notification/id         nid
                                 :notification/user-id    uid
                                 :notification/created-at now
                                 :notification/token      token}]}]]
               (:_ result)))
        (is (= "New deck: Leia / Lake Country" (get-in result [:email :subject])))
        (is (= {:email    (:email result)
                :response (email/send-fx ctx (:email result))}
               (send-email ctx result)))))
    (testing "failed sends are errors so the job is logged"
      (is (nil? (check ctx {:email {:to "a@example.com"} :response {:status 202}})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (check ctx {:email {:to "a@example.com"} :response {:status 500}}))))))
