(ns com.budgetswu.model.sqlite
  (:require [com.biffweb.sqlite :as biff.sqlite]
            [com.budgetswu.schema :as schema]))

(def module
  (biff.sqlite/module
   {:biff.sqlite/columns        schema/columns
    :biff.sqlite/extra-init-sql schema/extra-init-sql
    :biff.sqlite/authorize      #'schema/authorize}))
