(ns com.budgetswu.model.request-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.model.request :as request]))

(deftest earliest-legal-set
  (testing "set 7 rotated out sets 1-3"
    (is (= 4 (request/earliest-legal-set 7)))
    (is (= 4 (request/earliest-legal-set 9))))
  (is (= 7 (request/earliest-legal-set 10)))
  (is (= 1 (request/earliest-legal-set 3))))

(deftest legal-cards-query
  (let [card-set (fn [id number published-on]
                   {:card-set/id           id
                    :card-set/number       number
                    :card-set/published-on published-on})
        sets     [(card-set "JTL" 4 "2025-03-14")
                  (card-set "JTLP" 4 nil)
                  (card-set "HMW" 9 "2026-10-09")]]
    (is (nil? (request/legal-cards-query sets "XYZ")))
    (is (= [:or
            [:>= :card-set/number 4]
            [:and
             [:= :card-set/number nil]
             [:>= :card-set/published-on "2025-03-14"]]]
           (-> (request/legal-cards-query sets "HMW")
               :where
               last
               second
               :where
               last)))))

(deftest search-terms
  (is (= ["luke" "sky"] (request/search-terms "  Luke  SKY ")))
  (is (= [] (request/search-terms "   ")))
  (is (= [] (request/search-terms nil))))

(deftest like-pattern
  (is (= "%l%u%k%e%" (request/like-pattern "luke")))
  (is (= "%1%0%\\%%\\_%" (request/like-pattern "10%_"))))

(deftest search-score
  (is (nil? (request/search-score ["xyz"] "Luke Skywalker, Faithful Friend")))
  (is (nil? (request/search-score ["luke" "vader"] "Luke Skywalker, Faithful Friend")))
  (is (some? (request/search-score ["lsk"] "Luke Skywalker, Faithful Friend")))
  (is (> (request/search-score ["sky"] "Luke Skywalker, Faithful Friend")
         (request/search-score ["sky"] "Snoke, Shadowy Lord"))))

(deftest rank-matches
  (is (= ["Daring Raid"
          "Darth Vader, Commanding the First Legion"
          "Vader's Lightsaber"]
         (request/rank-matches ["dar"]
                               identity
                               ["Vader's Lightsaber"
                                "Daring Raid"
                                "Darth Vader, Commanding the First Legion"
                                "Luke Skywalker, Faithful Friend"])))
  (is (= ["Vader's Lightsaber" "Darth Vader, Commanding the First Legion"]
         (request/rank-matches ["vader"]
                               identity
                               ["Vader's Lightsaber"
                                "Darth Vader, Commanding the First Legion"]))))

(defn- resolve-with [resolver ctx handler]
  ((:biff.graph/resolve-fn resolver)
   (merge {:biff.graph/input {}
           :biff.fx/handlers {:biff.sqlite.fx/execute (fn [_ s] (handler s))}}
          ctx)))

(def tab-id #uuid "00000000-0000-0000-0000-000000000001")

(deftest card-page
  (let [card    {:card/id "Lake Country" :card/type "Base" :card/price-cents 25}
        handler (fn [{:keys [from select]}]
                  (case from
                    :card-set  [{:card-set/id "HMW" :card-set/number 9}]
                    :meta      [{:meta/set-id "HMW"}]
                    :tab-state [{:tab-state/card-page 2}]
                    :card      (if (= [[[:count :*] :n]] select)
                                 [{:n 51}]
                                 [(assoc card :printing/external-uid "8487171598")])))]
    (is (= {:request/card-page {:card-page/number     2
                                :card-page/page-count 2
                                :card-page/search     ""

                                :card-page/cards
                                [(assoc card :card/external-uid "8487171598")]}}
           (resolve-with request/card-page {:biff.datastar/tab-id tab-id} handler)))
    (testing "no current meta yet"
      (is (= 0
             (get-in (resolve-with request/card-page {:params {}}
                                   (fn [_] []))
                     [:request/card-page :card-page/page-count]))))))

(deftest card-page-search
  (let [card    (fn [id cents]
                  {:card/id id :card/type "Unit" :card/price-cents cents})
        queries (atom [])
        handler (fn [{:keys [from where]}]
                  (case from
                    :card-set  [{:card-set/id "HMW" :card-set/number 9}]
                    :meta      [{:meta/set-id "HMW"}]
                    :tab-state [{:tab-state/card-search " dar  vad "
                                 :tab-state/card-page   1}]
                    :card      (do (swap! queries conj where)
                                   [(card "Vader's Lightsaber" 500)
                                    (card "Darth Vader, Commanding the First Legion"
                                          100)])))
        result  (resolve-with request/card-page
                              {:biff.datastar/tab-id tab-id}
                              handler)]
    (is (= {:card-page/number     1
            :card-page/page-count 1
            :card-page/search     "dar  vad"}
           (dissoc (:request/card-page result) :card-page/cards)))
    (is (= ["Darth Vader, Commanding the First Legion" "Vader's Lightsaber"]
           (mapv :card/id (get-in result [:request/card-page :card-page/cards]))))
    (is (= [[:like :card/id [:escape "%d%a%r%" "\\"]]
            [:like :card/id [:escape "%v%a%d%" "\\"]]]
           (subvec (first @queries) 4)))))

(deftest page
  (is (= {:request/page 3}
         (resolve-with request/page {:params {:page 3}} (fn [_] []))))
  (is (nil? (resolve-with request/page {:params {:page 0}} (fn [_] []))))
  (is (nil? (resolve-with request/page {:params {:page "3"}} (fn [_] [])))))

(deftest card-search
  (let [signals (fn [s] {:biff.datastar/signals {:com.budgetswu/card-search s}})]
    (is (= {:request/card-search "luke"}
           (resolve-with request/card-search (signals "luke") (fn [_] []))))
    (is (= 100 (count (:request/card-search
                       (resolve-with request/card-search
                                     (signals (apply str (repeat 150 "a")))
                                     (fn [_] []))))))
    (is (nil? (resolve-with request/card-search (signals nil) (fn [_] []))))))

(deftest notification
  (let [token   #uuid "00000000-0000-0000-0000-000000000009"
        nid     #uuid "00000000-0000-0000-0000-000000000002"
        queries (atom [])
        handler (fn [statement]
                  (swap! queries conj statement)
                  [{:notification/id nid}])]
    (is (= {:request/notification {:notification/id nid}}
           (resolve-with request/notification {:path-params {:notification-token token}} handler)))
    (is (= [:= :notification/token token]
           (get-in (first @queries) [:where 1])))
    (is (= {} (resolve-with request/notification {:path-params {:notification-token "x"}} handler)))
    (is (nil? (resolve-with request/notification
                            {:path-params {:notification-token token}}
                            (fn [_] []))))))

(deftest email-notifications
  (is (= {:request/email-notifications false}
         (resolve-with request/email-notifications {:params {:enabled false}} (fn [_] []))))
  (is (nil? (resolve-with request/email-notifications
                          {:params {:enabled "false"}}
                          (fn [_] [])))))

(deftest tab-state
  (is (= {:request/tab-state {:tab-state/id tab-id}}
         (resolve-with request/tab-state {:biff.datastar/tab-id tab-id} (fn [_] []))))
  (is (nil? (resolve-with request/tab-state {} (fn [_] [])))))

(deftest decklist
  (is (= {:request/decklist {:decklist/id "deck"}}
         (resolve-with request/decklist {:params {:decklist-id "deck"}}
                       (fn [_] [{:decklist/id "deck"}]))))
  (is (nil? (resolve-with request/decklist {:params {:decklist-id "gone"}} (fn [_] []))))
  (is (= {} (resolve-with request/decklist {:params {}} (fn [_] [])))))

(deftest selected
  (is (= {:request/selected false}
         (resolve-with request/selected {:params {:selected false}} (fn [_] []))))
  (is (nil? (resolve-with request/selected {:params {:selected "true"}} (fn [_] [])))))

(deftest quantity
  (is (= {:request/quantity 2}
         (resolve-with request/quantity {:params {:quantity 2}} (fn [_] []))))
  (is (nil? (resolve-with request/quantity {:params {:quantity 4}} (fn [_] []))))
  (is (nil? (resolve-with request/quantity {:params {:quantity "2"}} (fn [_] [])))))

(deftest card
  (is (= {:request/card {:card/id "x"}}
         (resolve-with request/card {:params {:card-id "x"}}
                       (fn [_] [{:card/id "x"}]))))
  (is (nil? (resolve-with request/card {:params {:card-id "y"}} (fn [_] []))))
  (is (= {} (resolve-with request/card {:params {}} (fn [_] [])))))
