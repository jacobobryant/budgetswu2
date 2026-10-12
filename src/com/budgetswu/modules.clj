(ns com.budgetswu.modules
  (:require [com.biffweb.background :as biff.background]
            [com.biffweb.config :as biff.config]
            [com.biffweb.datastar :as biff.datastar]
            [com.biffweb.fx :as biff.fx]
            [com.biffweb.graph :as biff.graph]
            [com.biffweb.ring :as biff.ring]
            [com.budgetswu.app.admin :as app.admin]
            [com.budgetswu.app.auth :as app.auth]
            [com.budgetswu.app.cards :as app.cards]
            [com.budgetswu.app.for-you :as app.for-you]
            [com.budgetswu.app.landing :as app.landing]
            [com.budgetswu.app.landing.dev :as app.landing.dev]
            [com.budgetswu.fx :as fx]
            [com.budgetswu.lib.ui :as lib.ui]
            [com.budgetswu.model.sqlite :as model.sqlite]
            [com.budgetswu.model.user :as model.user]
            [com.budgetswu.model.global :as model.global]
            [com.budgetswu.model.request :as model.request]
            [com.budgetswu.work.sync :as work.sync]))

(def modules
  [{:biff.core/init {:biff.ring/on-error #'lib.ui/on-error}}
   (biff.config/module)
   (biff.ring/module)
   (biff.datastar/module)
   (biff.background/module)
   (biff.fx/module)
   fx/module
   (biff.graph/module)
   model.user/module
   model.sqlite/module
   model.global/module
   model.request/module
   work.sync/module
   app.admin/module
   app.landing/module
   app.landing.dev/module
   app.auth/module
   app.for-you/module
   app.cards/module])

(def start-order
  [:biff.config/module
   :biff.sqlite/module
   :biff.admin/module
   :biff.background/module
   :biff.ring/module])
