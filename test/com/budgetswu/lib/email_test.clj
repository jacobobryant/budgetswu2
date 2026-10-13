(ns com.budgetswu.lib.email-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.budgetswu.lib.email :as email]))

(def message
  {:to "a@example.com" :subject "Hi" :text "Hello" :html "<p>Hello</p>"})

(deftest send-fx
  (testing "MailerSend is used when configured"
    (let [[k request] (email/send-fx {:mailersend/api-key (delay "key")
                                      :mailersend/from    "from@example.com"}
                                     message)]
      (is (= :biff.fx/http k))
      (is (= "Bearer key" (get-in request [:headers "Authorization"])))
      (is (= [{:email "a@example.com"}] (get-in request [:form-params :to])))))
  (testing "otherwise the email is logged"
    (let [[k level text] (email/send-fx {} message)]
      (is (= [:com.budgetswu.fx/log :info] [k level]))
      (is (re-find #"Subject: Hi" text)))))

(deftest failure
  (is (nil? (email/failure {:status 202})))
  (is (nil? (email/failure nil)))
  (is (= {:message "bad"} (email/failure {:status 422 :body {:message "bad"}}))))

(deftest send-email
  (let [[start finish] (email/send-email)]
    (is (= {:response (email/send-fx {} message)} (start {} message)))
    (is (true? (finish {} {:response {:status 202}})))
    (is (true? (finish {} {:response nil})))
    (let [result (finish {} {:response {:status 500 :body "oops"}})]
      (is (false? (:biff.fx/return result)))
      (is (= [:com.budgetswu.fx/log :warn "MailerSend error: \"oops\""] (:_ result))))))
