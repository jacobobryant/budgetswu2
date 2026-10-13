(ns com.budgetswu.app.cards
  (:require [com.biffweb.datastar :as biff.datastar :refer [signal-name]]
            [com.biffweb.fx :refer [defpipeline]]
            [com.biffweb.ring :refer [defpath]]
            [com.budgetswu.lib.middleware :as mid]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]
            [com.budgetswu.schema :as schema]))

(defpath set-quantity-path "/cards/quantity")
(defpath set-search-path "/cards/search")
(defpath set-page-path "/cards/page")

;;;; Page ======================================================================

(defn- quantity-button [card-id owned n]
  [:button {:type          "button"
            :aria-pressed  (str (= n owned))
            :data-action   (set-quantity-path {:card-id card-id :quantity n})
            :data-on:click "@post(el.dataset.action)"
            :class         (into ["h-8 w-8 rounded-md text-sm font-semibold"
                                  "sm:h-9 sm:w-9"]
                                 (if (= n owned)
                                   ["bg-orange-700 text-white"]
                                   ["bg-stone-100 text-stone-700"
                                    "hover:bg-orange-100"]))}
   (str n)])

(defn- quantity-control [card-id owned]
  [:div {:class ["flex gap-1"] :role "group" :aria-label "Copies owned"}
   (mapv #(quantity-button card-id owned %)
         (range (inc schema/max-owned-quantity)))])

(defn- icon
  "An inline 16x16 stroked SVG icon drawn with `paths`."
  [class & paths]
  (into [:svg {:xmlns           "http://www.w3.org/2000/svg"
               :viewBox         "0 0 16 16"
               :fill            "none"
               :stroke          "currentColor"
               :stroke-width    "1.75"
               :stroke-linecap  "round"
               :stroke-linejoin "round"
               :aria-hidden     "true"
               :class           (into ["inline-block h-4 w-4 shrink-0"] class)}]
        (mapv (fn [d] [:path {:d d}]) paths)))

(defn- external-link-icon []
  (icon ["ml-1 -mt-0.5 align-middle"]
        "M7 3H4.5A1.5 1.5 0 0 0 3 4.5v7A1.5 1.5 0 0 0 4.5 13h7a1.5 1.5 0 0 0 1.5-1.5V9"
        "M9.5 3H13v3.5"
        "M13 3 7.5 8.5"))

(defn- search-icon [class]
  (icon class "M7 12A5 5 0 1 0 7 2a5 5 0 0 0 0 10Z" "m13.5 13.5-3-3"))

(defn- arrow-left-icon []
  (icon [] "M13 8H3" "M7 4 3 8l4 4"))

(defn- arrow-right-icon []
  (icon [] "M3 8h10" "M9 4l4 4-4 4"))

(defn- card-title
  "The card's name, linked to its starwarsunlimited.com page when known. The
  last word and icon are kept together so the icon never wraps on its own."
  [id external-uid]
  (if external-uid
    (let [[_ head tail] (re-matches #"(?s)(.*?)(\S+)" id)]
      [:a {:href   (str "https://starwarsunlimited.com/cards?cid=" external-uid)
           :target "_blank"
           :rel    "noopener noreferrer"
           :class  ["underline-offset-2 hover:underline"]}
       head
       [:span {:class ["whitespace-nowrap"]} tail (external-link-icon)]])
    id))

(defn- card-row [owned {:card/keys [id price-cents external-uid]
                        card-type  :card/type}]
  [:li {:key   id
        :class ["flex flex-wrap items-center justify-between gap-x-4 gap-y-2"
                "px-4 py-3 sm:px-6"]}
   [:div {:class ["min-w-0 flex-1"]}
    [:p {:class ["font-semibold leading-snug"]} (card-title id external-uid)]
    [:p {:class ["text-sm text-stone-500"]}
     (str card-type " · ")
     [:span {:class ["font-semibold text-orange-700"]}
      (recommendations/money price-cents)]]]
   (quantity-control id (get owned id 0))])

(defn- search-bar
  "A search input that filters the list as the user types. The query is kept in
  a signal rather than a value attribute so rerenders don't clobber typing."
  []
  [:div {:role                    "search"
         :class                   ["relative mb-4"]
         :data-signals__ifmissing (biff.datastar/signals-json {:tab/card-search ""})}
   [:label {:for "card-search" :class ["sr-only"]} "Search cards"]
   (search-icon ["pointer-events-none absolute left-3 top-1/2 -translate-y-1/2"
                 "text-stone-400"])
   [:input {:id                            "card-search"
            :type                          "search"
            :placeholder                   "Search cards"
            :autocomplete                  "off"
            :maxlength                     (str schema/max-search-length)
            :data-bind                     (signal-name :tab/card-search)
            :data-action                   (set-search-path)
            :data-on:input__debounce.200ms "@post(el.dataset.action)"
            :class                         ["w-full rounded-md border-0"
                                            "bg-white py-2 pl-9 pr-3"
                                            "text-stone-900"
                                            "placeholder:text-stone-400"
                                            "focus:outline-none focus:ring-2"
                                            "focus:ring-orange-500"]}]])

(defn- page-button [number enabled & content]
  (into [:button {:type          "button"
                  :disabled      (not enabled)
                  :data-action   (set-page-path {:page number})
                  :data-on:click "@post(el.dataset.action)"
                  :class         ["inline-flex items-center gap-2 rounded-md"
                                  "bg-stone-800 px-4 py-2 text-sm font-semibold"
                                  "text-stone-100 hover:bg-stone-700"
                                  "disabled:cursor-not-allowed disabled:bg-stone-800"
                                  "disabled:text-stone-500"]}]
        content))

(defn- pagination [class {:card-page/keys [number page-count]}]
  (when (pos? page-count)
    [:nav {:class (into ["flex items-center justify-between gap-4"] class)}
     (page-button (dec number) (< 1 number) (arrow-left-icon) "Previous")
     [:span {:class ["text-sm text-stone-300"]}
      (str "Page " number " of " page-count)]
     (page-button (inc number) (< number page-count) "Next" (arrow-right-icon))]))

(defn- card-list [owned cards]
  [:ul {:class ["divide-y divide-stone-200 rounded-lg bg-white text-stone-900"
                "shadow-lg"]}
   (mapv (partial card-row owned) cards)])

(defn- message [text]
  [:p {:class ["rounded-lg bg-stone-800 p-6 text-stone-300 sm:p-8"]} text])

(defpipeline cards-page
  [:biff.graph.fx/query
   [{:session/user [{:user/owned-cards
                     [:owned-card/card-id :owned-card/quantity]}]}
    {:request/card-page
     [:card-page/number
      :card-page/page-count
      :card-page/search
      {:card-page/cards [:card/id
                         :card/type
                         :card/price-cents
                         [:? :card/external-uid]]}]}]]

  (fn [request {:keys [session/user request/card-page]}]
    (let [{:card-page/keys [cards search]} card-page

          owned (into {}
                      (map (juxt :owned-card/card-id :owned-card/quantity))
                      (:user/owned-cards user))]
      (ui/app-page
       request
       (ui/shell
        request
        {:current (routes/cards)}
        (if (empty? owned)
          [:div {:class ["mb-6 rounded-lg bg-amber-200 p-4 text-stone-900 sm:p-6"]}
           [:h2 {:class ["text-lg font-bold"]} "Welcome to BudgetSWU"]
           [:p {:class ["mt-1"]}
            (str "Mark how many copies of each card you own. Recommended decks are "
                 "selected based on the cost of cards you still need to buy.")]]
          [:p {:class ["mb-6 max-w-2xl text-stone-300"]}
           "Deck prices only count the cards you don't already own."])
        (search-bar)
        (pagination ["mb-4"] card-page)
        (cond
          (seq cards)  (card-list owned cards)
          (seq search) (message "No cards match your search.")
          :else        (message "Cards are being prepared. Please check back later."))
        (pagination ["mt-6"] card-page))))))

;;;; Actions ===================================================================

(defpipeline set-quantity
  [:biff.graph.fx/query [{:request/card [:card/id]} [:? :request/quantity]]]

  (fn [{:keys [session] :biff.fx/keys [random-uuid7-seq]}
       {:keys [request/card request/quantity]}]
    (if (and card quantity)
      {:_      [:biff.sqlite.fx/authorized-write
                {:insert-into   :owned-card
                 :values        [{:owned-card/id       (first random-uuid7-seq)
                                  :owned-card/user-id  (:uid session)
                                  :owned-card/card-id  (:card/id card)
                                  :owned-card/quantity quantity}]
                 :on-conflict   [:owned-card/user-id :owned-card/card-id]
                 :do-update-set [:owned-card/quantity]}]
       :status 204}
      {:status 400})))

;; Search and page live in tab state so that rerenders (e.g. after setting a
;; card count) keep showing the same results.
(defpipeline set-search
  [:biff.graph.fx/query [[:? :request/card-search]]]

  (fn [{:keys [session biff.datastar/tab-id]} {:keys [request/card-search]}]
    (if (and tab-id card-search)
      {:_      [:biff.sqlite.fx/authorized-write
                {:insert-into   :tab-state
                 :values        [{:tab-state/id          tab-id
                                  :tab-state/user-id     (:uid session)
                                  :tab-state/card-search card-search
                                  :tab-state/card-page   1}]
                 :on-conflict   [:tab-state/id]
                 :do-update-set [:tab-state/card-search :tab-state/card-page]}]
       :status 204}
      {:status 400})))

(defpipeline set-page
  [:biff.graph.fx/query [[:? :request/page]]]

  (fn [{:keys [session biff.datastar/tab-id]} {:keys [request/page]}]
    (if (and tab-id page)
      {:_      [:biff.sqlite.fx/authorized-write
                {:insert-into   :tab-state
                 :values        [{:tab-state/id        tab-id
                                  :tab-state/user-id   (:uid session)
                                  :tab-state/card-page page}]
                 :on-conflict   [:tab-state/id]
                 :do-update-set [:tab-state/card-page]}]
       :status 204}
      {:status 400})))

(def module
  {:biff.ring/routes
   ["" {:middleware [mid/wrap-signed-in]}
    [(routes/cards)       {:get cards-page}]
    [(set-quantity-path)  {:post set-quantity}]
    [(set-search-path)    {:post set-search}]
    [(set-page-path)      {:post set-page}]]})
