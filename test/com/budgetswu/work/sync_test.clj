(ns com.budgetswu.work.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.work.sync :as sync]))

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

(deftest fetch-json-states
  (let [[request parse] (sync/fetch-json)
        [_ params]      (request {} "https://example.com" "test-key")]
    (is (= "Bearer test-key"
           (get-in params [:headers "Authorization"])))
    (is (= {:cards [{:id "x"}]}
           (parse {} {:body "{\"cards\":[{\"id\":\"x\"}]}"})))
    (testing "provider error responses are rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse {} {:body "{\"success\":false}"}))))))

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
