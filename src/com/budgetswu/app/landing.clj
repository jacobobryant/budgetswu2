(ns com.budgetswu.app.landing
  (:require [com.biffweb.fx :refer [defpipeline]]
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

(defn wrap-redirect-signed-in
  "Sends signed-in users to the app, unless the request has a
  `redirect=false` query param."
  [handler]
  (fn [{:keys [session params] :as ctx}]
    (if (and (some? (:uid session))
             (not= "false" (:redirect params)))
      {:status  303
       :headers {"location" (routes/app)}}
      (handler ctx))))

(def module
  {:biff.ring/routes
   ["" {}
    [(routes/home) {:middleware [wrap-redirect-signed-in]
                    :get        home}]]})
