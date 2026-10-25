(ns com.budgetswu.model.tournament-test
  (:require [clojure.test :refer [deftest is]]
            [com.budgetswu.model.tournament :as tournament]))

(deftest held-on
  (is (= {:tournament/held-on "2026-10-10"}
         ((:biff.graph/resolve-fn tournament/held-on)
          {:biff.graph/input {:tournament/held-at "2026-10-10T23:00:00Z"}}))))
