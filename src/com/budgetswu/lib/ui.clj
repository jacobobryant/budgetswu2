(ns com.budgetswu.lib.ui
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(def ^:private static-files
  [(:ui/icon default-page-opts) "/css/main.css" "/js/main.js"])

(defn static-paths
  "Returns a map from each static file's path to the path with a cache-busting
  query param. Reads resource metadata, so it's called once at startup."
  []
  (into {}
        (map (fn [path]
               [path (if-some [last-modified (some-> (io/resource (str "public" path))
                                                     ring-response/resource-data
                                                     :last-modified
                                                     (.getTime))]
                       (str path "?t=" last-modified)
                       path)]))
        static-files))

(defn- static-path [{:keys [com.budgetswu/static-paths]} path]
  (get static-paths path path))

(defn html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (chassis/html body)})

(defn absolute-url
  "Returns `path` prefixed with the configured base URL, for links that leave
  the site (emails, social media previews). Falls back to a relative path when
  the base URL isn't configured (e.g. in development)."
  [{:keys [com.budgetswu/base-url]} path]
  (str (some-> base-url (str/replace #"/+$" "")) path))

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
          [:link {:rel "icon" :href (static-path opts icon)}])
        [:link {:rel "stylesheet" :href (static-path opts "/css/main.css")}]
        [:script {:src (static-path opts "/js/main.js")}]
        [:script {:type "module" :src datastar-script-url}]]
       [:body (merge {:class ["absolute min-h-full w-full flex flex-col"]}
                     (when init-datastar
                       (biff.datastar/init-opts)))
        contents]]])))

(defn app-page [{:keys [biff.datastar/sse-request] :as ctx} & content]
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

(defn admin?
  "True when the signed-in user is the one configured as
  :biff.admin/admin-user-id (compared as strings, like Biff's dashboard does)."
  [{:keys [session] :biff.admin/keys [admin-user-id]}]
  (let [uid (str (:uid session))]
    (and (not (str/blank? uid))
         (= uid (str admin-user-id)))))

(defn- nav [ctx current]
  [:nav {:class ["flex flex-wrap items-center gap-x-4 gap-y-2 text-sm sm:gap-x-6"]}
   (nav-link (routes/decks) "Decks" current)
   (nav-link (routes/cards) "Cards" current)
   (nav-link (routes/settings) "Settings" current)
   (when (admin? ctx)
     (nav-link (routes/admin) "Admin" current))
   [:button {:type          "button"
             :data-action   (routes/signout)
             :data-on:click (str "@post(el.dataset.action).then(() => "
                                 "window.location.href = '" (routes/home) "')")
             :class         ["font-semibold text-stone-300 hover:text-white"]}
    "Sign out"]])

(defn shell
  "Wraps page content in the site layout. `current` is the path of the
  signed-in page being shown, or nil for public pages."
  [ctx {:keys [current description]} & content]
  [:div {:class ["flex-1 bg-stone-900"]}
   [:main {:class ["mx-auto w-full max-w-5xl px-4 py-8 sm:px-5 sm:py-12"]}
    [:header {:class ["mb-6 border-b border-stone-700 pb-6 sm:mb-10 sm:pb-8"]}
     [:div {:class ["flex flex-wrap items-center justify-between gap-x-6 gap-y-3"]}
      [:a {:href (cond-> (routes/home)
                   (some? (get-in ctx [:session :uid])) (str "?redirect=false"))}
       [:h1 {:class ["text-2xl font-black uppercase tracking-[0.2em]"
                     "text-amber-400 sm:text-3xl"]}
        "BudgetSWU"]]
      (if current
        (nav ctx current)
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
