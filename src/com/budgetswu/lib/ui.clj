(ns com.budgetswu.lib.ui
  (:require [clojure.java.io :as io]
            [dev.onionpancakes.chassis.core :as chassis]
            [com.biffweb.datastar :as biff.datastar]
            [com.budgetswu.routes :as routes]
            [ring.util.response :as ring-response]))

(def default-page-opts
  {:ui/title       "BudgetSWU"
   :ui/description nil
   :ui/lang        "en"
   :ui/image       "/preview.png"
   :ui/icon        "/favicon.ico"})

(def ^:private datastar-script-url
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js")

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (chassis/html body)})

(defn- absolute-url
  "Social media previews require absolute image URLs. Falls back to a relative
  path when the domain isn't configured (e.g. in development)."
  [{:keys [biff.tasks/domain]} path]
  (str (when domain (str "https://" domain)) path))

(defn page [opts & contents]
  (let [{:ui/keys [title description lang image icon init-datastar]}
        (merge default-page-opts opts)]
    (html-response
     [chassis/doctype-html5
      [:html {:lang lang :class ["min-h-full h-auto"]}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        (when title
          [[:title title]
           [:meta {:content title :property "og:title"}]])
        (when description
          [[:meta {:name "description" :content description}]
           [:meta {:property "og:description" :content description}]])
        (when image
          [[:meta {:content (absolute-url opts image) :property "og:image"}]
           [:meta {:content "summary_large_image" :name "twitter:card"}]])
        (when icon
          [:link {:rel "icon" :href (static-path icon)}])
        [:link {:rel "stylesheet" :href (static-path "/css/main.css")}]
        [:script {:src (static-path "/js/main.js")}]
        [:script {:type "module" :src datastar-script-url}]]
       [:body (merge {:class ["absolute min-h-full w-full flex flex-col"]}
                     (when init-datastar
                       (biff.datastar/init-opts)))
        contents]]])))

(defn app-page [{:keys [biff.datastar/sse-request] :as ctx}
                & content]
  (let [content* [:div#biff-datastar-content
                  {:class ["flex min-h-full flex-1 flex-col"]}
                  content]]
    (if sse-request
      (html-response content*)
      (page (assoc ctx :ui/init-datastar true) content*))))

(def link-class
  ["font-semibold text-orange-400 underline-offset-4 hover:underline"])

(defn- nav-link [href label current]
  [:a {:href  href
       :class (if (= href current)
                ["font-semibold text-amber-400"]
                ["font-semibold text-stone-300 hover:text-white"])}
   label])

(defn- nav [current]
  [:nav {:class ["flex items-center gap-4 text-sm sm:gap-6"]}
   (nav-link (routes/for-you) "Decks" current)
   (nav-link (routes/cards) "Your cards" current)
   [:form {:method "post" :action (routes/signout)}
    [:button {:type  "submit"
              :class ["font-semibold text-stone-300 hover:text-white"]}
     "Sign out"]]])

(defn shell
  "Wraps page content in the site layout. `current` is the path of the
  signed-in page being shown, or nil for public pages."
  [{:keys [current description]} & content]
  [:div {:class ["flex-1 bg-stone-900"]}
   [:main {:class ["mx-auto w-full max-w-5xl px-4 py-8 sm:px-5 sm:py-12"]}
    [:header {:class ["mb-6 border-b border-stone-700 pb-6 sm:mb-10 sm:pb-8"]}
     [:div {:class ["flex flex-wrap items-center justify-between gap-x-6 gap-y-3"]}
      [:a {:href (routes/home)}
       [:h1 {:class ["text-2xl font-black uppercase tracking-[0.2em]"
                     "text-amber-400 sm:text-3xl"]}
        "BudgetSWU"]]
      (if current
        (nav current)
        [:a {:href (routes/app) :class link-class} "Sign in"])]
     (when description
       [:p {:class ["mt-3 max-w-2xl text-stone-300"]} description])]
    content]])

(defn on-error [{:keys [status] :as ctx}]
  (-> (page
       ctx
       [:h1
        (if (= status 404)
          "Page not found."
          "Something went wrong.")])
      (assoc :status status)))
