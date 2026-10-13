(ns com.budgetswu.lib.pareto-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.lib.pareto :as pareto]))

(defn- option [id price percentile frequency]
  {:id id :price price :percentile percentile :frequency frequency})

(def objectives
  {:minimize  [:price :frequency]
   :maximize  [:percentile]
   :tie-break :id})

(deftest front
  (let [cheap    (option "a" 100 1/2 5)
        better   (option "b" 100 3/4 5)
        equal    (option "c" 100 3/4 5)
        rare     (option "d" 100 1/2 1)
        stronger (option "e" 200 9/10 5)
        worse    (option "f" 300 1/2 5)
        front    #(mapv :id (pareto/front % objectives))]
    (testing "equal minimized values keep only the better maximized value"
      (is (= ["b"] (front [cheap better]))))
    (testing "a better minimized value isn't dominated"
      (is (= ["d" "b"] (front [better rare]))))
    (testing "ordered by the minimized keys, then the tie-break"
      (is (= ["d" "b" "c" "e"]
             (front [worse equal stronger cheap better rare]))))))
