(ns com.budgetswu.modules
  (:require [com.biffweb.background :as biff.background]
            [com.biffweb.config :as biff.config]
            [com.biffweb.datastar :as biff.datastar]
            [com.biffweb.fx :as biff.fx]
            [com.biffweb.graph :as biff.graph]
            [com.biffweb.ring :as biff.ring]
            [com.budgetswu.app.admin :as app.admin]
            [com.budgetswu.app.auth :as app.auth]
            [com.budgetswu.app.demo :as app.demo]
            [com.budgetswu.app.landing :as app.landing]
            [com.budgetswu.lib.ui :as lib.ui]
            [com.budgetswu.model.sqlite :as model.sqlite]
            [com.budgetswu.model.tab :as model.tab]
            [com.budgetswu.model.user :as model.user]))

(def modules
  [{:biff.core/init {:biff.ring/on-error #'lib.ui/on-error}}
   (biff.config/module)
   (biff.ring/module)
   (biff.datastar/module)
   (biff.background/module)
   (biff.fx/module)
   (biff.graph/module)
   model.user/module
   model.sqlite/module
   model.tab/module
   app.admin/module
   app.landing/module
   app.auth/module
   app.demo/module])

(def start-order
  [:biff.config/module
   :biff.sqlite/module
   :biff.admin/module
   :biff.background/module
   :biff.ring/module])
