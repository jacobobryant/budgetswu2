(ns com.budgetswu.schema
  (:require [com.biffweb.sqlite.schema :as s]))

(def max-owned-quantity 3)

(def max-search-length 100)

;; :user/seen-recommendations is the last set of recommended decklist IDs the
;; user saw on the Decks page or was emailed about. Notifications are sent for
;; decks not in it.
(def columns
  {:tab-state/id          s/pkey
   :tab-state/card-search (s/text)
   :tab-state/card-page   (s/int)

   :user/id                   s/pkey
   :user/email                (s/text :required :unique)
   :user/joined-at            (s/inst :required :index)
   :user/display-name         (s/text)
   :user/seen-recommendations (s/edn [:set :string])
   :user/unsubscribed-at      (s/inst)

   :notification/id         s/pkey
   :notification/user-id    (s/ref :user/id :required :index)
   :notification/created-at (s/inst :required :index)
   :notification/token      (s/uuid :required :unique)

   :card/id          (s/text {:primary-key true})
   :card/type        (s/text)
   :card/price-cents (s/int)

   :printing/id               (s/text {:primary-key true})
   :printing/card-id          (s/text :required :index)
   :printing/card-set-id      (s/text :required)
   :printing/collector-number (s/text :required :index)
   :printing/variant          (s/text)
   :printing/external-uid     (s/text)

   :card-set/id           (s/text {:primary-key true})
   :card-set/number       (s/int)
   :card-set/published-on (s/text)

   :card-aspect/id      (s/text {:primary-key true})
   :card-aspect/card-id (s/text :required :index)
   :card-aspect/aspect  (s/text :required)

   :meta/id         (s/text {:primary-key true})
   :meta/start-date (s/text :required)
   :meta/end-date   (s/text)
   :meta/is-current (s/bool :required)
   :meta/set-id     (s/text)

   :tournament/id           (s/text {:primary-key true})
   :tournament/name         (s/text :required)
   :tournament/held-at      (s/text :required)
   :tournament/player-count (s/int)
   :tournament/format       (s/text)

   :decklist/id            (s/text {:primary-key true})
   :decklist/melee-id      (s/text)
   :decklist/tournament-id (s/text :required :index)
   :decklist/player-name   (s/text)
   :decklist/leader-id     (s/text)
   :decklist/base-id       (s/text)
   :decklist/format        (s/text)

   :deck-card/id          (s/text {:primary-key true})
   :deck-card/decklist-id (s/text :required :index)
   :deck-card/card-id     (s/text :required)
   :deck-card/quantity    (s/int :required)

   :standing/id              (s/text {:primary-key true})
   :standing/decklist-id     (s/text :index)
   :standing/tournament-id   (s/text :required)
   :standing/final-placement (s/int)

   :owned-card/id       s/pkey
   :owned-card/user-id  (s/ref :user/id :required {:unique-with [:owned-card/card-id]})
   :owned-card/card-id  (s/text :required)
   :owned-card/quantity (s/int :required)

   :sync-state/id           (s/text {:primary-key true})
   :sync-state/watermark    (s/text)
   :sync-state/attempted-at (s/text)

   :recommendation/id   (s/text {:primary-key true})
   :recommendation/rank (s/int :required)})

;; Strings added here will be appended to resources/schema.sql
(def extra-init-sql [])

;; Add new columns here that you want users to be able to edit (e.g. settings).
(def editable-user-fields
  [:user/display-name :user/seen-recommendations :user/unsubscribed-at])

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

    :owned-card
    (and (every? #{uid} (keep :owned-card/user-id [before after]))
         (or (nil? after) (<= 0 (:owned-card/quantity after) max-owned-quantity)))

    false))

;; These authorization rules apply when using biff.sqlite/authorized-write and
;; are meant as an extra layer of protection.
(defn authorize
  [ctx diff]
  (every? #(authorize-entry ctx %) diff))
