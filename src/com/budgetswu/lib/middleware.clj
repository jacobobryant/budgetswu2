(ns com.budgetswu.lib.middleware
  (:require [com.budgetswu.routes :as routes]))

(defn wrap-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status  303
       :headers {"location" (routes/signin)}})))

(defn wrap-redirect-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      {:status  303
       :headers {"location" (routes/app)}}
      (handler ctx))))
