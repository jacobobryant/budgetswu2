(ns com.budgetswu.model.global-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.model.global :as global]))

(defn- resolve-with [resolver rows-by-table & {:keys [input] :or {input {}}}]
  ((:biff.graph/resolve-fn resolver)
   {:biff.graph/input input

    :biff.fx/handlers
    {:biff.sqlite.fx/execute
     (fn [_ statement]
       (get rows-by-table (:from statement) []))}}))

(deftest list-resolvers
  (let [cases [[global/sync-states :sync-state
                :global/sync-states {:sync-state/id "job"}]
               [global/metas :meta
                :global/metas {:meta/id "HMW" :meta/is-current true}]
               [global/tournaments :tournament
                :global/tournaments {:tournament/id "event"}]
               [global/card-sets :card-set
                :global/card-sets {:card-set/id           "SOR"
                                   :card-set/number       1
                                   :card-set/published-on "2024-03-08"}]
               [global/standard-printings :printing
                :global/standard-printings
                {:printing/card-id          "Lake Country"
                 :printing/collector-number "HMW_023"}]
               [global/ranked-recommendations :recommendation
                :global/ranked-recommendations {:recommendation/id "deck"}]
               [global/users :user
                :global/users {:user/id    "user"
                               :user/email "a@example.com"}]
               [global/notification-candidates :user
                :global/notification-candidates {:user/id "user"}]]]
    (doseq [[resolver table output-key row] cases]
      (is (= [row]
             (get (resolve-with resolver {table [row]}) output-key))))))

(deftest current-recommendations
  (is (= {:global/current-recommendations [{:recommendation/id "deck"}]}
         (resolve-with global/current-recommendations {:decklist [{:decklist/id "deck"}]}))))

(deftest front
  (let [candidate (fn [id eligible price]
                    {:recommendation/id                  id
                     :recommendation/eligible            eligible
                     :recommendation/price-cents         price
                     :recommendation/percentile          1/2
                     :recommendation/archetype-frequency 1})]
    (is (= {:global/front [{:recommendation/id "b"}]}
           (resolve-with global/front
                         {}
                         :input {:global/current-recommendations
                                 [(candidate "a" false 100)
                                  (candidate "b" true 200)
                                  (candidate "c" true 300)
                                  {:recommendation/id "d"}]})))))

(deftest stored-recommendations
  (is (= {:global/recommendations [{:recommendation/id "b"} {:recommendation/id "a"}]}
         (resolve-with global/stored-recommendations
                       {}
                       :input {:global/ranked-recommendations
                               [{:recommendation/id "b" :recommendation/eligible true}
                                {:recommendation/id "c" :recommendation/eligible false}
                                {:recommendation/id "gone"}
                                {:recommendation/id "a" :recommendation/eligible true}]}))))
