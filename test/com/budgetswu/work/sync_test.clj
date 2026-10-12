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
