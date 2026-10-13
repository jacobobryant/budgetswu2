(ns com.budgetswu.fx
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log])
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

(def module
  {:biff.fx/handlers
   {:com.budgetswu.fx/read-home-html  read-home-html
    :com.budgetswu.fx/write-home-html write-home-html
    :com.budgetswu.fx/log             log-message}})
