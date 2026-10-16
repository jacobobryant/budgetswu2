(ns com.budgetswu.app.landing
  (:require [com.biffweb.fx :refer [defpipeline]]
            [com.budgetswu.lib.middleware :as mid]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.routes :as routes]))

(defpipeline home
  [:com.budgetswu.fx/home-file]

  (fn [ctx file]
    (if file
      {:status  200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    file}
      (recommendations/home-response ctx []))))

(def module
  {:biff.ring/routes
   ["" {}
    [(routes/home) {:middleware [mid/wrap-redirect-signed-in]
                    :get        home}]]})
