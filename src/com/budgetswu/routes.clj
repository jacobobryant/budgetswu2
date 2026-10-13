(ns com.budgetswu.routes
  (:require [com.biffweb.ring :refer [defpath]]))

(defpath home "/")
(defpath app "/app")
(defpath decks "/decks")
(defpath cards "/cards")
(defpath settings "/settings")
(defpath unsubscribe "/unsubscribe/:token")
(defpath admin "/admin")
(defpath signout "/signout")
