(ns com.budgetswu.fx
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [hato.client :as hato])
  (:import [java.nio.file AtomicMoveNotSupportedException
            Files StandardCopyOption]))

(defn read-home-html [{:keys [com.budgetswu/home-file]}]
  (let [file (io/file home-file)]
    (when (.exists file)
      (slurp file))))

(defn write-home-html [{:keys [com.budgetswu/home-file]} html]
  (let [target (io/file home-file)]
    (io/make-parents target)
    (let [temp (java.io.File/createTempFile "home-" ".html"
                                            (.getParentFile target))]
      (try
        (spit temp html)
        (try
          (Files/move (.toPath temp) (.toPath target)
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING
                                   StandardCopyOption/ATOMIC_MOVE]))
          (catch AtomicMoveNotSupportedException _
            (Files/move (.toPath temp) (.toPath target)
                        (into-array StandardCopyOption
                                    [StandardCopyOption/REPLACE_EXISTING]))))
        (finally
          (Files/deleteIfExists (.toPath temp)))))
    home-file))

(defn log-message [_ level message]
  (log/log level message))

(defn melee-sign-in
  "Posts melee.gg's sign-in form. The credentials are read from ctx rather than
  passed as arguments so they can't end up in effect error logs, and the
  response doesn't include the request."
  [{:com.budgetswu/keys [melee-email melee-password]} {:keys [url token headers]}]
  (when-not (and melee-email melee-password)
    (throw (ex-info "MELEE_EMAIL and MELEE_PASSWORD must be set" {})))
  (-> (hato/request {:url              url
                     :method           :post
                     :headers          headers
                     :throw-exceptions false
                     :form-params      {"__RequestVerificationToken" token
                                        "ReturnUrl"                  "/"
                                        "Email"                      melee-email
                                        "Password"                   (force melee-password)}})
      (select-keys [:status :headers :body])))

(def module
  {:biff.fx/handlers
   {:com.budgetswu.fx/read-home-html  read-home-html
    :com.budgetswu.fx/write-home-html write-home-html
    :com.budgetswu.fx/log             log-message
    :com.budgetswu.fx/melee-sign-in   melee-sign-in}})
