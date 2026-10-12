(ns com.budgetswu.schema
  (:require [com.biffweb.sqlite.schema :as s :refer [?]]))

(def tab-state-schema
  [:map
   [:tab/background-color ? [:enum :white :red :blue :green]]])

(def columns
  {:tab-state/id   s/pkey
   :tab-state/data (s/edn tab-state-schema)

   :user/id           s/pkey
   :user/email        (s/text :required :unique)
   :user/joined-at    (s/inst :required :index)
   :user/display-name (s/text)

   :card/id               (s/text {:primary-key true})
   :card/collector-number (s/text :required :index)
   :card/name             (s/text :required)
   :card/variant          (s/text)
   :card/set-code         (s/text)
   :card/type             (s/text)

   :card-aspect/id      (s/text {:primary-key true})
   :card-aspect/card-id (s/text :required :index)
   :card-aspect/aspect  (s/text :required)

   :meta/id         (s/text {:primary-key true})
   :meta/start-date (s/text :required)
   :meta/end-date   (s/text)
   :meta/is-current (s/bool :required)

   :tournament/id           (s/text {:primary-key true})
   :tournament/name         (s/text :required)
   :tournament/held-at      (s/text :required)
   :tournament/player-count (s/int)
   :tournament/format       (s/text)

   :decklist/id            (s/text {:primary-key true})
   :decklist/melee-id      (s/text)
   :decklist/tournament-id (s/text :required :index)
   :decklist/player-name   (s/text)
   :decklist/leader-name   (s/text)
   :decklist/base-name     (s/text)
   :decklist/base-number   (s/text)
   :decklist/format        (s/text)

   :deck-card/id               (s/text {:primary-key true})
   :deck-card/decklist-id      (s/text :required :index)
   :deck-card/collector-number (s/text :required)
   :deck-card/quantity         (s/int :required)

   :standing/id              (s/text {:primary-key true})
   :standing/decklist-id     (s/text :index)
   :standing/tournament-id   (s/text :required)
   :standing/final-placement (s/int)

   :card-price/id         (s/text {:primary-key true})
   :card-price/cents      (s/int :required)
   :card-price/product-id (s/int :required)

   :sync-state/id           (s/text {:primary-key true})
   :sync-state/watermark    (s/text)
   :sync-state/attempted-at (s/text)

   :recommendation/id   (s/text {:primary-key true})
   :recommendation/rank (s/int :required)})

;; Strings added here will be appended to resources/schema.sql
(def extra-init-sql [])

;; Add new columns here that you want users to be able to edit (e.g. settings).
(def editable-user-fields [:user/display-name])

(defn only-fields-edited? [before after fields]
  (= (apply dissoc before fields)
     (apply dissoc after fields)))

(defn authorize-entry [{{:keys [uid]} :session
                        :keys         [biff.datastar/tab-id]}
                       {:keys [table op before after]}]
  (case table
    :user
    (and (every? #{uid} (keep :user/id [before after]))
         (case op
           :create false
           :update (only-fields-edited? before after editable-user-fields)
           :delete true))

    :tab-state
    (every? #{tab-id} (keep :tab-state/id [before after]))

    false))

;; These authorization rules apply when using biff.sqlite/authorized-write and
;; are meant as an extra layer of protection.
(defn authorize
  [ctx diff]
  (every? #(authorize-entry ctx %) diff))
