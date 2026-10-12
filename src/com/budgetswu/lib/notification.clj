(ns com.budgetswu.lib.notification
  "Notification emails about new deck recommendations."
  (:require [clojure.string :as str]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]
            [dev.onionpancakes.chassis.core :as chassis]))

(defn decklist-label [{:recommendation/keys [leader-name base-name]}]
  (str (or leader-name "Unknown leader") " / " (or base-name "Unknown base")))

(defn subject
  "`new-decks` are in the order shown on the Decks page; the subject names the
  first one."
  [new-decks]
  (let [label  (decklist-label (first new-decks))
        others (dec (count new-decks))]
    (case others
      0 (str "New deck: " label)
      1 (str "New decks: " label " and 1 other")
      (str "New decks: " label " and " others " others"))))

(defn- deck-lines
  "The website's deck card as plain-text lines."
  [rank {:recommendation/keys [melee-id player-name tournament-name tournament-date
                               player-count placement price-cents
                               full-price-cents popularity-rank archetype-count]
         :as                  deck}]
  (cond-> [(str rank ". " (decklist-label deck))
           (str "   " (or player-name "Unknown player") " · "
                (or tournament-name "Unknown tournament") " · "
                (recommendations/day tournament-date))
           (str "   Your cost: " (recommendations/money price-cents)
                (when (not= price-cents full-price-cents)
                  (str " (full " (recommendations/money full-price-cents) ")"))
                " · Placed " placement " of " player-count
                " · Popularity #" popularity-rank " of " archetype-count)]
    melee-id (conj (str "   " (recommendations/melee-url melee-id)))))

(def ^:private email-styles
  {:body  "margin:0;padding:24px 12px;background:#1c1917;font-family:Arial,sans-serif"
   :wrap  "max-width:600px;margin:0 auto"
   :brand "color:#fbbf24;font-size:22px;font-weight:900;letter-spacing:4px"
   :intro "color:#d6d3d1;font-size:16px;line-height:1.5;margin:16px 0"
   :card  "background:#ffffff;border-radius:8px;padding:16px;margin:0 0 12px"
   :title "color:#1c1917;font-size:18px;font-weight:bold;margin:0"
   :rank  "color:#a8a29e"
   :base  "color:#57534e;font-size:14px;margin:2px 0 0"
   :meta  "color:#78716c;font-size:13px;margin:8px 0 0"
   :stat  "background:#f5f5f4;border-radius:6px;padding:8px 10px;vertical-align:top"
   :label "color:#78716c;font-size:11px;letter-spacing:1px"
   :value "color:#1c1917;font-size:16px;font-weight:bold"
   :price "color:#c2410c;font-size:16px;font-weight:bold"
   :note  "color:#78716c;font-size:12px"
   :link  "color:#c2410c;font-size:14px;font-weight:bold"
   :cta   (str "display:inline-block;background:#c2410c;color:#ffffff;"
               "font-weight:bold;padding:12px 20px;border-radius:6px;"
               "text-decoration:none;margin:8px 0 24px")
   :foot  "color:#a8a29e;font-size:12px;line-height:1.5"})

(defn- email-stat [label value-style value & [note]]
  [:td {:style (:stat email-styles) :width "33%"}
   [:div {:style (:label email-styles)} label]
   [:div {:style value-style} value]
   (when note
     [:div {:style (:note email-styles)} note])])

(defn- email-deck
  "The website's deck card, with inline styles for email clients."
  [rank {:recommendation/keys [melee-id leader-name base-name player-name
                               tournament-name tournament-date player-count
                               placement price-cents full-price-cents
                               popularity-rank archetype-count]}]
  [:div {:style (:card email-styles)}
   [:p {:style (:title email-styles)}
    [:span {:style (:rank email-styles)} (str rank ". ")]
    (or leader-name "Unknown leader")]
   [:p {:style (:base email-styles)} (or base-name "Unknown base")]
   [:p {:style (:meta email-styles)}
    (str (or player-name "Unknown player") " · "
         (or tournament-name "Unknown tournament") " · "
         (recommendations/day tournament-date))]
   [:table {:width "100%" :cellspacing "6" :cellpadding "0" :style "margin:8px -6px 0"}
    [:tr
     (email-stat "YOUR COST" (:price email-styles) (recommendations/money price-cents)
                 (when (not= price-cents full-price-cents)
                   (str "Full " (recommendations/money full-price-cents))))
     (email-stat "PLACED" (:value email-styles) (str placement " of " player-count))
     (email-stat "POPULARITY" (:value email-styles)
                 (str "#" popularity-rank " of " archetype-count))]]
   (when melee-id
     [:p {:style "margin:12px 0 0"}
      [:a {:href (recommendations/melee-url melee-id) :style (:link email-styles)}
       "View decklist on melee.gg"]])])

(defn email
  "Returns the email for `new-decks`, numbered by their position in the user's
  full list of `recommendations`."
  [{:com.budgetswu/keys [mailing-address] :as ctx} email recommendations new-decks token]
  (let [rank-by-id  (into {}
                          (map-indexed (fn [i deck] [(:recommendation/id deck) (inc i)]))
                          recommendations)
        ranked      (mapv (juxt #(rank-by-id (:recommendation/id %)) identity) new-decks)
        decks-url   (ui/absolute-url ctx (routes/decks))
        unsub-url   (ui/absolute-url ctx (routes/unsubscribe token))
        intro       (if (= 1 (count new-decks))
                      "There's a new deck recommendation for you on BudgetSWU:"
                      (str "There are " (count new-decks)
                           " new deck recommendations for you on BudgetSWU:"))
        footer-text (str "You're getting this email because you have a BudgetSWU "
                         "account. We send at most one email a day.")]
    {:to      email
     :subject (subject new-decks)
     :text    (str/join "\n"
                        (-> [intro ""]
                            (into (mapcat (fn [[rank deck]]
                                            (conj (deck-lines rank deck) "")))
                                  ranked)
                            (into [(str "See all your recommendations: " decks-url)
                                   ""
                                   footer-text
                                   (str "Unsubscribe: " unsub-url)
                                   mailing-address])))
     :html    (chassis/html
               [chassis/doctype-html5
                [:html
                 [:head [:meta {:charset "utf-8"}]]
                 [:body {:style (:body email-styles)}
                  [:div {:style (:wrap email-styles)}
                   [:div {:style (:brand email-styles)} "BUDGETSWU"]
                   [:p {:style (:intro email-styles)} intro]
                   (mapv (fn [[rank deck]] (email-deck rank deck)) ranked)
                   [:a {:href decks-url :style (:cta email-styles)}
                    "See all your recommendations"]
                   [:p {:style (:foot email-styles)}
                    footer-text " "
                    [:a {:href unsub-url :style "color:#a8a29e"} "Unsubscribe"]
                    [:br]
                    mailing-address]]]]])}))

(defn insert
  "Records a notification email so its unsubscribe `token` works."
  [{:biff.fx/keys [now random-uuid7-seq]} user-id token]
  {:insert-into :notification
   :values      [{:notification/id         (first random-uuid7-seq)
                  :notification/user-id    user-id
                  :notification/created-at now
                  :notification/token      token}]})
