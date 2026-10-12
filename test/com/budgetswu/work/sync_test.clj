(ns com.budgetswu.work.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.lib.notification-test :refer [deck]]
            [com.budgetswu.work.sync :as sync]
            [tick.core :as tick]))

(deftest database-pipeline-states
  (let [statement {:select [:card/id] :from :card}]
    (is (= [:biff.sqlite.fx/execute statement]
           ((first (sync/execute)) {} statement)))
    (is (= [:biff.sqlite.fx/execute-tx [statement]]
           ((first (sync/execute-tx)) {} [statement])))
    (is (= [:biff.graph.fx/query [:global/front]]
           ((first (sync/graph-query)) {} [:global/front])))
    (is (= [:com.budgetswu.fx/write-home-html "<html></html>"]
           ((first (sync/write-home)) {} "<html></html>")))))

(deftest request-states
  (let [[request] (sync/request)
        [_ req]   (request {:com.budgetswu/base-url "https://example.com/"}
                           {:url "https://example.com/x"})]
    (is (= {:url     "https://example.com/x"
            :method  :get
            :headers {"User-Agent"
                      "Mozilla/5.0 (compatible; BudgetSWU/1.0; +https://example.com/)"}}
           req))))

(deftest melee-sign-in-states
  (let [[page sign-in finish] (sync/melee-sign-in)
        page-response         {:body    (str "<input name=\"__RequestVerificationToken\""
                                             " type=\"hidden\" value=\"tok\" />")
                               :headers {"set-cookie" "a=1; path=/; HttpOnly"}}
        result                (sign-in {} page-response)]
    (is (= :biff.fx/http (first (page {}))))
    (is (= [:com.budgetswu.fx/melee-sign-in
            {:url     "https://melee.gg/Account/SignInPassword"
             :token   "tok"
             :headers {"User-Agent"       (sync/user-agent {})
                       "Cookie"           "a=1"
                       "X-Requested-With" "XMLHttpRequest"}}]
           (:response result)))
    (testing "the session cookie is added to the sign-in page's cookies"
      (is (= "a=1; b=2; c=3"
             (finish {} (assoc result :response
                               {:status  200
                                :body    "{\"RedirectUrl\":\"/\"}"
                                :headers {"set-cookie" ["b=2; path=/" "c=3"]}})))))
    (testing "failed sign-ins are rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (finish {} (assoc result :response
                                     {:status 200
                                      :body   "{\"Error\":true}"})))))
    (testing "a missing sign-in form is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sign-in {} {:body "<html></html>"}))))))

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
         (sync/card-id {:name "Luke Skywalker" :subtitle "Faithful Friend"})))
  (is (= "Battlefield Marine"
         (sync/card-id {:name "Battlefield Marine" :subtitle nil})))
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
  (let [meta {:id "HMW" :start "2026-10-09"}]
    (is (= "2026-10-09" (sync/decklist-search-start meta nil)))
    (is (= "2026-10-09" (sync/decklist-search-start meta "2026-07-01")))
    (is (= "2026-10-20" (sync/decklist-search-start meta "2026-10-20")))
    (is (= "2026-10-09" (sync/next-decklist-watermark meta (tick/date "2026-10-12"))))
    (is (= "2026-10-23" (sync/next-decklist-watermark meta (tick/date "2026-10-30"))))))

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

(def ctx
  {:com.budgetswu/base-url        "https://example.com/"
   :com.budgetswu/mailing-address "1 Main St"})

(def token #uuid "00000000-0000-0000-0000-000000000009")

(deftest notify-user-states
  (let [[decide send-email] (sync/notify-user)

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
    (testing "users without a stored set start tracking without an email"
      (is (= {:_ [:biff.sqlite.fx/execute (seen #{"a" "b"})]}
             (decide ctx user))))
    (testing "removed decks alone don't trigger an email"
      (is (= {} (decide ctx (assoc user :user/seen-recommendations #{"a" "b" "c"})))))
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
        (is (= [:com.budgetswu.fx/send-email (:email result)]
               (send-email ctx result)))))
    (is (nil? (send-email ctx {})))))
