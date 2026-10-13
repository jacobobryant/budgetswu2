(ns com.budgetswu.app.landing.dev
  (:require [com.biffweb.fx :refer [defpipeline]]
            [com.biffweb.ring :refer [defpath]]
            [com.budgetswu.lib.recommendations :as recommendations]))

(defpath dev-home-path "/_dev/home")

(defpipeline dev-home
  (fn [ctx]
    (if (:com.budgetswu/dev-mode ctx)
      [:biff.graph.fx/query
       [{:global/recommendations recommendations/fields}]]
      {:biff.fx/return {:status 404 :body "Not found"}}))

  (fn [ctx {:keys [global/recommendations]}]
    (recommendations/home-response ctx recommendations)))

(def module
  {:biff.ring/routes
   ["" {}
    [(dev-home-path) {:get dev-home}]]})
