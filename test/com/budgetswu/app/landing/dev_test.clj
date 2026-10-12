(ns com.budgetswu.app.landing.dev-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.app.landing.dev :as dev]))

(deftest dev-home-access
  (let [state (first (dev/dev-home))]
    (is (= {:biff.fx/return {:status 404 :body "Not found"}}
           (state {:com.budgetswu/dev-mode false})))
    (is (= :biff.graph.fx/query
           (first (state {:com.budgetswu/dev-mode true}))))))
