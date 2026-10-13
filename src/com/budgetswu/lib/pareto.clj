(ns com.budgetswu.lib.pareto
  "Multi-objective selection: the items that no other item beats on every
  objective.")

(defn- dominates? [{:keys [minimize maximize]} a b]
  (let [a-mins (mapv a minimize)
        b-mins (mapv b minimize)
        a-maxs (mapv a maximize)
        b-maxs (mapv b maximize)]
    (and (every? true? (mapv <= a-mins b-mins))
         (every? true? (mapv >= a-maxs b-maxs))
         (or (some true? (mapv < a-mins b-mins))
             (some true? (mapv > a-maxs b-maxs))))))

(defn front
  "Returns the `items` that aren't dominated by another item, sorted by the
  `minimize` keys in order and then `tie-break`. `minimize` and `maximize` are
  keys whose values must not be nil."
  [items {:keys [minimize tie-break] :as objectives}]
  (->> items
       (filterv (fn [item]
                  (not-any? #(dominates? objectives % item) items)))
       (sort-by (apply juxt (conj minimize tie-break)))
       vec))
