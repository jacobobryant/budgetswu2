(ns com.budgetswu.model.user-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.model.user :as user]))

(defn- resolve-with [resolver ctx rows-by-table]
  ((:biff.graph/resolve-fn resolver)
   (merge
    {:biff.graph/input {:user/id #uuid "00000000-0000-0000-0000-000000000001"}

     :biff.fx/handlers
     {:biff.sqlite.fx/execute
      (fn [_ statement]
        (get rows-by-table (:from statement) []))}}
    ctx)))

(def uid-1 #uuid "00000000-0000-0000-0000-000000000001")
(def uid-2 #uuid "00000000-0000-0000-0000-000000000002")

(deftest owned-cards
  (let [row {:owned-card/id       #uuid "00000000-0000-0000-0000-000000000003"
             :owned-card/user-id  uid-2
             :owned-card/card-id  "x"
             :owned-card/quantity 2}]
    (is (= [{:user/owned-cards []}
            {:user/owned-cards [(dissoc row :owned-card/user-id)]}]
           (resolve-with user/owned-cards
                         {:biff.graph/input [{:user/id uid-1} {:user/id uid-2}]}
                         {:owned-card [row]})))))

(deftest recommendation-candidates
  (let [query  (atom nil)
        result ((:biff.graph/resolve-fn user/recommendation-candidates)
                {:biff.graph/input {:user/id uid-1}
                 :biff.fx/handlers {:biff.graph.fx/query
                                    (fn [_ q]
                                      (reset! query q)
                                      {:global/current-recommendations
                                       [{:recommendation/id "a"} {:recommendation/id "b"}]})}})]
    (is (= [{:global/current-recommendations [:recommendation/id]}] @query))
    (is (= {:user/recommendation-candidates
            [{:recommendation/id "a" :recommendation/user {:user/id uid-1}}
             {:recommendation/id "b" :recommendation/user {:user/id uid-1}}]}
           result))))

(deftest recommendations
  (let [user      {:user/id uid-1}
        candidate (fn [id eligible price]
                    {:recommendation/id                  id
                     :recommendation/user                user
                     :recommendation/eligible            eligible
                     :recommendation/price-cents         price
                     :recommendation/percentile          1/2
                     :recommendation/archetype-frequency 1})]
    (is (= {:user/recommendations [{:recommendation/id "b" :recommendation/user user}]}
           (resolve-with user/recommendations
                         {:biff.graph/input
                          {:user/recommendation-candidates
                           [(candidate "a" false 100)
                            (candidate "b" true 200)
                            (candidate "c" true 300)
                            {:recommendation/id "d" :recommendation/user user}]}}
                         {})))))
