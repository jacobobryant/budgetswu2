(ns com.budgetswu.lib.middleware
  (:require [com.biffweb.ring :refer [defpath]]))

(defpath signin "/signin")

(defn wrap-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status  303
       :headers {"location" (signin)}})))
