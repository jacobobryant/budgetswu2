(ns com.budgetswu.model.sqlite
  (:require [com.biffweb.sqlite :as biff.sqlite]
            [com.budgetswu.schema :as schema]))

;; biff.sqlite/module minus its lifecycle: litestream, migrations, sqldef, and
;; connections are started as separate modules (see modules/start-order) so
;; that migrations can run between the litestream restore and sqldef. This
;; keeps the rest of the module, including biff.sqlite's KV table columns.
(def module
  (dissoc (biff.sqlite/module
           {:biff.sqlite/columns        schema/columns
            :biff.sqlite/extra-init-sql schema/extra-init-sql
            :biff.sqlite/authorize      #'schema/authorize})
          :biff.core/id
          :biff.core/start
          :biff.core/stop))
