(ns com.budgetswu.lib.recommendations
  (:require [com.budgetswu.lib.ui :as ui]
            [tick.core :as tick]))

(def fields
  "The recommendation attributes needed to show a recommendation."
  [:recommendation/id
   [:? :recommendation/melee-id]
   [:? :recommendation/leader-name]
   [:? :recommendation/base-name]
   [:? :recommendation/player-name]
   :recommendation/tournament-name
   :recommendation/tournament-date
   :recommendation/player-count
   :recommendation/placement
   :recommendation/price-cents
   :recommendation/full-price-cents
   :recommendation/popularity-rank
   :recommendation/archetype-count])

(def candidate-fields
  "The recommendation attributes needed to choose recommendations."
  [:recommendation/id
   [:? :recommendation/eligible]
   [:? :recommendation/price-cents]
   [:? :recommendation/percentile]
   [:? :recommendation/archetype-frequency]])

(def objectives
  "Recommendations are the eligible decks that no other deck beats on price,
  placement, and archetype rarity."
  {:minimize  [:recommendation/price-cents :recommendation/archetype-frequency]
   :maximize  [:recommendation/percentile]
   :tie-break :recommendation/id})

(defn money [cents]
  (format "$%.2f" (/ cents 100.0)))

(def description
  [(str "Decklists for Star Wars: Unlimited, recommended based on price, "
        "performance, and popularity. Sign in to get recommendations based on "
        "the cards you already own. ")
   [:a {:href  "https://obryant.dev/p/budgetswu"
        :class "text-orange-400 underline-offset-4 hover:underline"}
    "About"] "."])

(defn melee-url [melee-id]
  (str "https://melee.gg/Decklist/View/" melee-id))

(defn day
  "Returns the UTC date of a tournament's start time."
  [tournament-date]
  (some-> tournament-date tick/instant (tick/in "UTC") tick/date str))

(defn- stat [label value & [note]]
  [:div {:class ["min-w-0 rounded-md bg-stone-100 px-2 py-2 sm:px-3"]}
   [:dt {:class ["text-xs font-medium uppercase tracking-wide text-stone-500"]}
    label]
   [:dd {:class ["mt-1 whitespace-nowrap text-sm font-semibold sm:text-lg"]}
    value]
   (when note
     [:dd {:class ["whitespace-nowrap text-xs text-stone-500"]} note])])

(defn- item [personal i {:recommendation/keys [id melee-id leader-name base-name
                                               player-name tournament-name
                                               tournament-date player-count
                                               placement price-cents
                                               full-price-cents popularity-rank
                                               archetype-count]}]
  [:li {:key   id
        :class ["rounded-lg bg-white p-4 text-stone-900 shadow-lg sm:p-6"]}
   [:h2 {:class ["text-lg font-semibold leading-snug sm:text-xl"]}
    [:span {:class ["text-stone-400"]} (str (inc i) ". ")]
    (or leader-name "Unknown leader")]
   [:p {:class ["text-sm font-medium text-stone-600"]}
    (or base-name "Unknown base")]
   [:p {:class ["mt-2 text-sm text-stone-500"]}
    (str (or player-name "Unknown player") " · "
         (or tournament-name "Unknown tournament") " · ")
    [:span {:class ["whitespace-nowrap"]}
     (day tournament-date)]]
   [:dl {:class ["mt-4 grid gap-2 sm:gap-3"
                 "grid-cols-[repeat(auto-fit,minmax(5.5rem,1fr))]"]}
    (if personal
      (stat "Your cost"
            [:span {:class ["text-orange-700"]} (money price-cents)]
            (when (not= price-cents full-price-cents)
              (str "Full " (money full-price-cents))))
      (stat "Price" [:span {:class ["text-orange-700"]} (money price-cents)]))
    (stat "Placed" (str placement " of " player-count))
    (stat "Popularity" (str "#" popularity-rank " of " archetype-count))]
   (when melee-id
     [:a {:href   (melee-url melee-id)
          :target "_blank"
          :rel    "noopener noreferrer"
          :class  ["mt-4 block rounded-md border border-orange-300"
                   "py-2 text-center text-sm font-semibold"
                   "text-orange-700 hover:bg-orange-50"
                   "sm:inline-block sm:border-0 sm:p-0 sm:text-left"
                   "sm:underline sm:hover:bg-transparent"]}
      "View decklist on melee.gg"])])

(defn recommendation-list
  "Renders ranked recommendations. When `personal` is true, prices are the cost
  of the cards the user doesn't own."
  [recommendations {:keys [personal]}]
  (if (seq recommendations)
    [:ol {:class ["grid gap-4 sm:gap-5"]}
     (into [] (map-indexed (partial item personal)) recommendations)]
    [:p {:class ["rounded-lg bg-stone-800 p-6 text-stone-300 sm:p-8"]}
     "Recommendations are being prepared. Please check back later."]))

(defn home-response [ctx recommendations]
  (ui/page
   (assoc ctx
          :ui/title       "BudgetSWU — Star Wars: Unlimited decklists"
          :ui/description (str "Decklists for Star Wars: Unlimited, recommended "
                               "based on price, performance, and popularity."))
   (ui/shell ctx {:description description}
             (recommendation-list recommendations {}))))
