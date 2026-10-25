(ns com.budgetswu.app.admin
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [com.biffweb.admin :as biff.admin]
            [com.biffweb.fx :refer [defpipeline]]
            [com.biffweb.ring :refer [defpath]]
            [com.budgetswu.lib.email :as email]
            [com.budgetswu.lib.middleware :as mid]
            [com.budgetswu.lib.notification :as notification]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]
            [tick.core :as tick]))

(defpath biff-admin "/_biff/admin")
(defpath select-deck-path "/admin/select-deck")
(defpath send-test-email-path "/admin/test-email")
(defpath regenerate-home-path "/admin/regenerate-home")
(defpath sync-path "/admin/sync")

(defn- save-tab-state
  "Returns an effect that upserts the tab's admin state."
  [tab-id user-id fields]
  [:biff.sqlite.fx/authorized-write
   {:insert-into   :tab-state
    :values        [(merge {:tab-state/id tab-id :tab-state/user-id user-id} fields)]
    :on-conflict   [:tab-state/id]
    :do-update-set (vec (keys fields))}])

;;;; Page ======================================================================

(defn- panel [attrs & content]
  (into [:section (merge {:class ["rounded-lg bg-white p-4 text-stone-900 shadow-lg sm:p-6"]}
                         attrs)]
        content))

(defn- deck-option [selected i {:recommendation/keys [id label price-cents tournament-day]}]
  (let [checked (contains? selected id)]
    [:li
     [:label {:class ["flex cursor-pointer items-start gap-3 rounded-md px-2 py-2"
                      "hover:bg-stone-100"]}
      [:input {:type           "checkbox"
               :checked        checked
               :data-action    (select-deck-path {:decklist-id id :selected (not checked)})
               :data-on:change "@post(el.dataset.action)"
               :class          ["mt-1 h-4 w-4 shrink-0 accent-orange-700"]}]
      [:span {:class ["min-w-0"]}
       [:span {:class ["block font-medium"]}
        [:span {:class ["text-stone-400"]} (str (inc i) ". ")]
        label]
       [:span {:class ["block text-sm text-stone-500"]}
        (str "Your cost " (recommendations/money price-cents) " · " tournament-day)]]]]))

(defn- timestamp [s]
  (some-> s tick/instant (tick/in "UTC") tick/date-time (tick/truncate :minutes)
          (str/replace "T" " ") (str " UTC")))

(defpipeline admin-page
  [:biff.graph.fx/query
   [{:session/user [:user/email
                    {:user/recommendations [:recommendation/id
                                            :recommendation/label
                                            :recommendation/price-cents
                                            :recommendation/tournament-day]}]}
    {:global/sync-job [:?sync-state/completed-at]}
    {:global/recent-tournaments [:tournament/name
                                 :tournament/held-on
                                 :?tournament/player-count]}
    {:?request/tab-state [:?tab-state/admin-selected
                          :?tab-state/admin-status
                          :?tab-state/home-status
                          :?tab-state/sync-status]}]]

  (fn [request {:keys        [session/user request/tab-state]
                :global/keys [sync-job recent-tournaments]}]
    (let [{:user/keys [email recommendations]} user

          {:tab-state/keys [admin-selected admin-status home-status sync-status]} tab-state

          completed-at (:sync-state/completed-at sync-job)]
      (ui/app-page
       request
       (ui/shell
        request
        {:current (routes/admin)}
        [:div {:class ["grid gap-4 sm:gap-5"]}
         (panel
          {}
          [:h2 {:class ["text-lg font-semibold"]} "Biff dashboard"]
          [:p {:class ["mt-2 text-stone-600"]}
           "Users, metrics, profiling, and alerts. "
           [:a {:href  (biff-admin)
                :class ["font-semibold text-orange-700 underline-offset-4"
                        "hover:underline"]}
            "Open the dashboard"]])
         (panel
          {}
          [:h2 {:class ["text-lg font-semibold"]} "Data sync"]
          [:p {:class ["mt-2 text-stone-600"]}
           "Last completed: "
           [:span {:class ["font-medium text-stone-900"]}
            (or (timestamp completed-at) "never")]]
          [:div {:class ["mt-4 flex flex-wrap items-center gap-4"]}
           [:button {:type          "button"
                     :data-action   (sync-path)
                     :data-on:click "@post(el.dataset.action)"
                     :class         ["rounded-md bg-orange-700 px-4 py-2 text-sm"
                                     "font-semibold text-white hover:bg-orange-800"]}
            "Sync now"]
           [:span {:class ["text-sm text-stone-600"]} sync-status]]
          [:h3 {:class ["mt-5 font-semibold"]} "Most recent tournaments"]
          (if (seq recent-tournaments)
            [:ul {:class ["mt-2 grid gap-1 text-sm"]}
             (mapv (fn [{:tournament/keys [held-on player-count] :as tournament}]
                     [:li
                      [:span {:class ["font-medium"]} (:tournament/name tournament)]
                      [:span {:class ["text-stone-500"]}
                       (str " · " held-on
                            (when player-count (str " · " player-count " players")))]])
                   recent-tournaments)]
            [:p {:class ["mt-2 text-sm text-stone-500"]} "No tournaments yet."]))
         (panel
          {}
          [:h2 {:class ["text-lg font-semibold"]} "Home page"]
          [:p {:class ["mt-2 text-stone-600"]}
           (str "The home page is a static file that the sync job writes. "
                "This rewrites it from the current recommendations.")]
          [:div {:class ["mt-4 flex flex-wrap items-center gap-4"]}
           [:button {:type          "button"
                     :data-action   (regenerate-home-path)
                     :data-on:click "@post(el.dataset.action)"
                     :class         ["rounded-md bg-orange-700 px-4 py-2 text-sm"
                                     "font-semibold text-white hover:bg-orange-800"]}
            "Regenerate home page"]
           [:span {:class ["text-sm text-stone-600"]} home-status]])
         (panel
          {}
          [:h2 {:class ["text-lg font-semibold"]} "Test notification email"]
          [:p {:class ["mt-2 text-stone-600"]}
           (str "Sends " email " a notification email listing the selected "
                "decks as new. Your seen decks aren't changed.")]
          (if (seq recommendations)
            [:ul {:class ["mt-4 grid gap-1"]}
             (into [] (map-indexed (partial deck-option (set admin-selected))) recommendations)]
            [:p {:class ["mt-4 text-stone-500"]} "You have no recommendations yet."])
          [:div {:class ["mt-4 flex flex-wrap items-center gap-4"]}
           [:button {:type          "button"
                     :disabled      (empty? admin-selected)
                     :data-action   (send-test-email-path)
                     :data-on:click "@post(el.dataset.action)"
                     :class         ["rounded-md bg-orange-700 px-4 py-2 text-sm"
                                     "font-semibold text-white hover:bg-orange-800"
                                     "disabled:cursor-not-allowed"
                                     "disabled:bg-stone-300 disabled:text-stone-500"]}
            "Send test email"]
           [:span {:class ["text-sm text-stone-600"]} admin-status]])])))))

;;;; Actions ===================================================================

(defpipeline select-deck
  [:biff.graph.fx/query
   [{:session/user [:user/id]}
    {:request/decklist [:decklist/id]}
    :request/selected
    {:request/tab-state [:tab-state/id :?tab-state/admin-selected]}]]

  (fn [_ {:keys [session/user request/decklist request/selected request/tab-state]}]
    (let [{:tab-state/keys [id admin-selected]} tab-state]
      {:_      (save-tab-state
                id
                (:user/id user)
                {:tab-state/admin-selected [:lift ((if selected conj disj)
                                                   (set admin-selected)
                                                   (:decklist/id decklist))]
                 :tab-state/admin-status   nil})
       :status 204})))

(defpipeline send-test-email
  [:biff.graph.fx/query
   [{:session/user [:user/id
                    :user/email
                    {:user/recommendations [:recommendation/id
                                            :recommendation/label
                                            :?recommendation/melee-url
                                            :?recommendation/leader-name
                                            :?recommendation/base-name
                                            :?recommendation/player-name
                                            :recommendation/tournament-name
                                            :recommendation/tournament-day
                                            :recommendation/player-count
                                            :recommendation/placement
                                            :recommendation/price-cents
                                            :recommendation/full-price-cents
                                            :recommendation/popularity-rank
                                            :recommendation/archetype-count]}]}
    {:request/tab-state [:tab-state/id :?tab-state/admin-selected]}]]

  (fn [{:biff.fx/keys [random-uuid4-seq] :as ctx}
       {:keys [session/user request/tab-state]}]
    (let [{:user/keys [id email recommendations]} user

          decks (filterv #(contains? (:tab-state/admin-selected tab-state) (:recommendation/id %))
                         recommendations)
          token (first random-uuid4-seq)]
      (if (empty? decks)
        {:biff.fx/return {:status 400}}
        ;; The record makes the email's unsubscribe link work.
        {:_       [:biff.sqlite.fx/authorized-write (notification/insert ctx id token)]
         :email   (notification/email ctx email recommendations decks token)
         :user-id id
         :tab-id  (:tab-state/id tab-state)})))

  (fn [ctx {:keys [email] :as result}]
    (assoc result :response (email/send-fx ctx email)))

  (fn [_ {:keys [email user-id tab-id response]}]
    {:_      (save-tab-state
              tab-id
              user-id
              {:tab-state/admin-selected [:lift #{}]
               :tab-state/admin-status   (if (email/failure response)
                                           (str "Couldn't send to " (:to email) ".")
                                           (str "Sent to " (:to email) "."))})
     :status 204}))

(defpipeline regenerate-home
  [:biff.graph.fx/query
   [{:session/user [:user/id]}
    {:global/recommendations
     [:recommendation/id
      :?recommendation/melee-url
      :?recommendation/leader-name
      :?recommendation/base-name
      :?recommendation/player-name
      :recommendation/tournament-name
      :recommendation/tournament-day
      :recommendation/player-count
      :recommendation/placement
      :recommendation/price-cents
      :recommendation/full-price-cents
      :recommendation/popularity-rank
      :recommendation/archetype-count]}
    {:request/tab-state [:tab-state/id]}]]

  (fn [ctx {:keys [session/user global/recommendations request/tab-state]}]
    {:_home   [:com.budgetswu.fx/write-home-html
               (:body (recommendations/home-response ctx recommendations))]
     :_status (save-tab-state
               (:tab-state/id tab-state)
               (:user/id user)
               {:tab-state/home-status (str "Regenerated with "
                                            (count recommendations)
                                            " decks.")})
     :status  204}))

(defpipeline request-sync
  [:biff.graph.fx/query [{:session/user [:user/id]} {:request/tab-state [:tab-state/id]}]]

  ;; With :requested-at, the sync runs even if it isn't due.
  (fn [{:biff.fx/keys [now]} {:keys [session/user request/tab-state]}]
    {:_       [:biff.background.fx/submit-jobs
               :com.budgetswu.work.sync/sync
               [{:requested-at now}]]
     :_status (save-tab-state
               (:tab-state/id tab-state)
               (:user/id user)
               {:tab-state/sync-status "Sync started."})
     :status  204}))

;;;; Biff dashboard ============================================================

(defpipeline get-users
  [:biff.graph.fx/query [{:global/users [:user/id :user/email :user/joined-at]}]]

  (fn [_ {:global/keys [users]}]
    (mapv #(set/rename-keys % {:user/id        :user-id
                               :user/email     :email
                               :user/joined-at :joined-at})
          users)))

(defn- get-usage-events [_ctx]
  ;; If you want to monitor usage, return maps from the past 37 days with keys
  ;; :user-id (any) and :instant (Instant).
  [])

(defn- get-revenue-events [_ctx]
  ;; If your app has revenue, return maps from the past 30 days with keys
  ;; :revenue (number) and :instant (Instant).
  [])

(def module
  (biff.admin/module
   {:biff.admin/get-usage-events   #'get-usage-events
    :biff.admin/get-revenue-events #'get-revenue-events
    :biff.admin/get-users          #'get-users
    :biff.admin/send-email         #'email/send-email}))

;;;; Routes ==================================================================

(defn wrap-admin
  "Shows everyone but the admin the usual not-found page, so the page's
  existence isn't revealed."
  [handler]
  (fn [ctx]
    (if (ui/admin? ctx)
      (handler ctx)
      (ui/on-error (assoc ctx :status 404)))))

(def page-module
  {:biff.ring/routes
   ["" {:middleware [mid/wrap-signed-in wrap-admin]}
    [(routes/admin)          {:get admin-page}]
    [(select-deck-path)      {:post select-deck}]
    [(send-test-email-path)  {:post send-test-email}]
    [(regenerate-home-path)  {:post regenerate-home}]
    [(sync-path)             {:post request-sync}]]})
