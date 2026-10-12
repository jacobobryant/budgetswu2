(ns com.budgetswu.lib.recommendations
  (:require [com.budgetswu.lib.ui :as ui]
            [clojure.string :as str]))

(defn- money [cents]
  (format "$%.2f" (/ cents 100.0)))

(defn- stat [label value]
  [:div {:class ["min-w-0 rounded-lg bg-stone-100 px-2 py-2 sm:px-3"]}
   [:dt {:class ["text-xs font-medium uppercase tracking-wide text-stone-500"]}
    label]
   [:dd {:class ["mt-1 whitespace-nowrap text-sm font-semibold sm:text-lg"]}
    value]])

(defn home-response [recommendations]
  (ui/page
   {:ui/title       "Budgetswu — Star Wars: Unlimited decklists"
    :ui/description "Affordable, high-performing Premier decklists."}
   [:div {:class ["flex-1 bg-stone-900"]}
    [:main {:class ["mx-auto w-full max-w-5xl px-4 py-8 sm:px-5 sm:py-12"]}
     [:header {:class ["mb-6 border-b border-stone-700 pb-6 sm:mb-10 sm:pb-8"]}
      [:h1 {:class ["text-2xl font-black uppercase tracking-[0.2em]"
                    "text-amber-400 sm:text-3xl"]}
       "Budgetswu"]
      [:p {:class ["mt-3 max-w-2xl text-stone-300"]}
       (str "Decklists for Star Wars: Unlimited, recommended based on price, "
            "performance, and popularity.")]]
     (if (seq recommendations)
       [:ol {:class ["grid gap-4 sm:gap-5"]}
        (into
         []
         (map-indexed
          (fn [i {:recommendation/keys [id melee-id leader-name base-name
                                        player-name tournament-name
                                        tournament-date player-count placement
                                        price-cents popularity-rank
                                        archetype-count]}]
            [:li {:key   id
                  :class ["rounded-xl bg-white p-4 text-stone-900 shadow-lg"
                          "sm:p-6"]}
             [:h2 {:class ["text-lg font-semibold leading-snug sm:text-xl"]}
              [:span {:class ["text-stone-400"]} (str (inc i) ". ")]
              (or leader-name "Unknown leader")]
             [:p {:class ["text-sm font-medium text-stone-600"]}
              (or base-name "Unknown base")]
             [:p {:class ["mt-2 text-sm text-stone-500"]}
              (str (or player-name "Unknown player") " · "
                   (or tournament-name "Unknown tournament") " · ")
              [:span {:class ["whitespace-nowrap"]}
               (first (str/split (or tournament-date "") #"T"))]]
             [:dl {:class ["mt-4 grid gap-2 sm:gap-3"
                           "grid-cols-[repeat(auto-fit,minmax(5.5rem,1fr))]"]}
              (stat "Price" [:span {:class ["text-orange-700"]}
                             (money price-cents)])
              (stat "Placed" (str placement " of " player-count))
              (stat "Popularity" (str "#" popularity-rank " of "
                                      archetype-count))]
             (when melee-id
               [:a {:href   (str "https://melee.gg/Decklist/View/" melee-id)
                    :target "_blank"
                    :rel    "noopener noreferrer"
                    :class  ["mt-4 block rounded-lg border border-orange-300"
                             "py-2 text-center text-sm font-semibold"
                             "text-orange-700 hover:bg-orange-50"
                             "sm:inline-block sm:border-0 sm:p-0 sm:text-left"
                             "sm:underline sm:hover:bg-transparent"]}
                "View decklist on melee.gg"])]))
         recommendations)]
       [:p {:class ["rounded-xl bg-stone-800 p-6 text-stone-300 sm:p-8"]}
        "Recommendations are being prepared. Please check back later."])]]))
