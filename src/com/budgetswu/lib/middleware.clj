(ns com.budgetswu.lib.middleware
  (:require [com.biffweb.ring :refer [defpath]]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]))

(defpath signin "/signin")

(defn wrap-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status  303
       :headers {"location" (signin)}})))

(defn wrap-redirect-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      {:status  303
       :headers {"location" (routes/app)}}
      (handler ctx))))

(defn wrap-admin
  "Shows everyone but the admin the usual not-found page, so the page's
  existence isn't revealed."
  [handler]
  (fn [ctx]
    (if (ui/admin? ctx)
      (handler ctx)
      (ui/on-error (assoc ctx :status 404)))))
