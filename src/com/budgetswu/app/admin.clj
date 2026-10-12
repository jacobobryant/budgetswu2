(ns com.budgetswu.app.admin
  (:require [clojure.set :as set]
            [com.biffweb.admin :as biff.admin]
            [com.biffweb.datastar :as biff.datastar :refer [signal-name]]
            [com.biffweb.fx :refer [defpipeline]]
            [com.biffweb.ring :refer [defpath]]
            [com.biffweb.sqlite :as biff.sqlite]
            [com.budgetswu.lib.email :as lib.email]
            [com.budgetswu.lib.middleware :as mid]
            [com.budgetswu.lib.notification :as notification]
            [com.budgetswu.lib.ranking :as ranking]
            [com.budgetswu.lib.recommendations :as recommendations]
            [com.budgetswu.lib.ui :as ui]
            [com.budgetswu.routes :as routes]))

(defpath send-test-email-path "/admin/test-email")

;;;; Page ======================================================================

(defn- panel [attrs & content]
  (into [:section (merge {:class ["rounded-lg bg-white p-4 text-stone-900 shadow-lg sm:p-6"]}
                         attrs)]
        content))

(defn- deck-option [i {:recommendation/keys [id price-cents tournament-date]
                       :as                  deck}]
  [:li
   [:label {:class ["flex cursor-pointer items-start gap-3 rounded-md px-2 py-2"
                    "hover:bg-stone-100"]}
    [:input {:type      "checkbox"
             :value     id
             :data-bind (signal-name :admin/selected)
             :class     ["mt-1 h-4 w-4 shrink-0 accent-orange-700"]}]
    [:span {:class ["min-w-0"]}
     [:span {:class ["block font-medium"]}
      [:span {:class ["text-stone-400"]} (str (inc i) ". ")]
      (notification/decklist-label deck)]
     [:span {:class ["block text-sm text-stone-500"]}
      (str "Your cost " (recommendations/money price-cents) " · "
           (recommendations/day tournament-date))]]]])

(defpipeline admin-page
  [:biff.graph.fx/query
   [{:session/user [:user/email {:user/recommendations ranking/fields}]}]]

  (fn [request {:keys [session/user]}]
    (let [{:user/keys [email recommendations]} user

          selected (str "$" (signal-name :admin/selected))
          status   (str "$" (signal-name :admin/status))]
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
           [:a {:href  (routes/biff-admin)
                :class ["font-semibold text-orange-700 underline-offset-4"
                        "hover:underline"]}
            "Open the dashboard"]])
         (panel
          {:data-signals__ifmissing (biff.datastar/signals-json
                                     {:admin/selected [] :admin/status ""})}
          [:h2 {:class ["text-lg font-semibold"]} "Test notification email"]
          [:p {:class ["mt-2 text-stone-600"]}
           (str "Sends " email " a notification email listing the selected "
                "decks as new. Your seen decks aren't changed.")]
          (if (seq recommendations)
            [:ul {:class ["mt-4 grid gap-1"]}
             (into [] (map-indexed deck-option) recommendations)]
            [:p {:class ["mt-4 text-stone-500"]} "You have no recommendations yet."])
          [:div {:class ["mt-4 flex flex-wrap items-center gap-4"]}
           [:button {:type               "button"
                     :disabled           true
                     :data-attr:disabled (str "!" selected ".some(Boolean)")
                     :data-action        (send-test-email-path)
                     :data-on:click      "@post(el.dataset.action)"
                     :class              ["rounded-md bg-orange-700 px-4 py-2 text-sm"
                                          "font-semibold text-white hover:bg-orange-800"
                                          "disabled:cursor-not-allowed"
                                          "disabled:bg-stone-300 disabled:text-stone-500"]}
            "Send test email"]
           [:span {:data-text status :class ["text-sm text-stone-600"]}]])])))))

;;;; Actions ===================================================================

(defpipeline send-test-email
  [:biff.graph.fx/query
   [{:session/user [:user/id :user/email {:user/recommendations ranking/fields}]}
    [:? :request/selected-recommendations]]]

  (fn [{:biff.fx/keys [random-uuid4-seq] :as ctx}
       {:keys [session/user request/selected-recommendations]}]
    (let [{:user/keys [id email recommendations]} user

          decks (filterv #(contains? selected-recommendations (:recommendation/id %))
                         recommendations)
          token (first random-uuid4-seq)]
      (if (empty? decks)
        {:biff.fx/return {:status 400}}
        ;; Notifications are written by the server rather than by users, so
        ;; there are no authorization rules for them. The record makes the
        ;; email's unsubscribe link work.
        {:_          [:biff.sqlite.fx/execute (notification/insert ctx id token)]
         :email      (notification/email ctx email recommendations decks token)
         :deck-count (count recommendations)})))

  (fn [_ {:keys [email] :as result}]
    (assoc result :_ [:com.budgetswu.fx/send-email email]))

  (fn [_ {:keys [email deck-count]}]
    ;; Checkboxes bound to an array signal each own one slot, holding the
    ;; deck's ID when checked and "" otherwise.
    (biff.datastar/patch-signals {:admin/selected (vec (repeat deck-count ""))
                                  :admin/status   (str "Sent to " (:to email) ".")})))

;;;; Biff dashboard ============================================================

(defn- get-users [ctx]
  (->> (biff.sqlite/execute ctx {:select   [:user/id
                                            :user/email
                                            :user/joined-at]
                                 :from     :user
                                 :order-by [[:user/joined-at :desc]]})
       (mapv #(set/rename-keys % {:user/id        :user-id
                                  :user/email     :email
                                  :user/joined-at :joined-at}))))

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
    :biff.admin/send-email         #'lib.email/send-email}))

(def page-module
  {:biff.ring/routes
   ["" {:middleware [mid/wrap-signed-in mid/wrap-admin]}
    [(routes/admin)          {:get admin-page}]
    [(send-test-email-path)  {:post send-test-email}]]})
