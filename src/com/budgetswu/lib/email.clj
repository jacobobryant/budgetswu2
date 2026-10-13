(ns com.budgetswu.lib.email
  (:require [clojure.string :as str]
            [com.biffweb.fx :refer [defpipeline]]))

(defn- mailersend-request
  [{:mailersend/keys [api-key from from-name reply-to]}
   {:keys [to subject html text]}]
  {:url              "https://api.mailersend.com/v1/email"
   :method           :post
   :headers          {"Authorization" (str "Bearer " (force api-key))}
   :content-type     :json
   :throw-exceptions false
   :as               :json
   :form-params      {:from     {:email from :name from-name}
                      :reply_to {:email reply-to :name from-name}
                      :to       [{:email to}]
                      :subject  subject
                      :html     html
                      :text     text}})

(defn- console-message [{:keys [to subject text]}]
  (str/join "\n" ["" "---" (str "To:      " to) (str "Subject: " subject) "" text "---" ""]))

(defn send-fx
  "Returns an effect descriptor that sends `message`, or that prints it when
  MailerSend isn't configured (e.g. in development)."
  [{:keys [mailersend/api-key] :as ctx} message]
  (if api-key
    [:biff.fx/http (mailersend-request ctx message)]
    [:com.budgetswu.fx/log :info (console-message message)]))

(defn failure
  "Returns the MailerSend error body for the result of `send-fx`'s effect, or
  nil if the email was sent."
  [{:keys [status body]}]
  (when (and status (<= 400 status))
    (or body {:status status})))

(defpipeline send-email
  (fn [ctx message]
    {:response (send-fx ctx message)})

  (fn [_ {:keys [response]}]
    (if-some [error (failure response)]
      {:_              [:com.budgetswu.fx/log :warn (str "MailerSend error: " (pr-str error))]
       :biff.fx/return false}
      true)))
