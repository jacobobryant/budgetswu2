(ns com.budgetswu.app.landing
  (:require [com.biffweb.fx :refer [defpipeline]]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.routes :as routes]))

(defpipeline home
  [:com.budgetswu.fx/read-home-html]

  (fn [_ html]
    (if html
      {:status  200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    html}
      (recommendations/home-response []))))

(def module
  {:biff.ring/routes
   ["" {}
    [(routes/home) {:get home}]]})
