(ns com.budgetswu.migration
  "Migrations for schema and data changes sqldef can't make on its own, such as
  adding foreign keys to existing SQLite tables. The DB's user_version is the
  number of migrations applied. Each migration hardcodes the tables it rebuilds;
  sqldef handles any later changes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; Each migration has :cleanup statements, which run first, and :rebuild, a map
;; from table name to the table's new column and constraint definitions.
(def migrations
  [;; 1: :ref columns get foreign keys. Rows that would violate them are
   ;; deleted, including whole decklists with unknown cards.
   {:cleanup
    ["DELETE FROM tab_state
      WHERE user_id IS NOT NULL AND user_id NOT IN (SELECT id FROM user)"
     "DELETE FROM printing
      WHERE card_id NOT IN (SELECT id FROM card)
         OR card_set_id NOT IN (SELECT id FROM card_set)"
     "DELETE FROM card_aspect WHERE card_id NOT IN (SELECT id FROM card)"
     "DELETE FROM meta
      WHERE set_id IS NOT NULL AND set_id NOT IN (SELECT id FROM card_set)"
     "DELETE FROM decklist
      WHERE tournament_id NOT IN (SELECT id FROM tournament)
         OR (leader_id IS NOT NULL AND leader_id NOT IN (SELECT id FROM card))
         OR (base_id IS NOT NULL AND base_id NOT IN (SELECT id FROM card))
         OR id IN (SELECT decklist_id FROM deck_card
                   WHERE card_id NOT IN (SELECT id FROM card))"
     "DELETE FROM deck_card WHERE decklist_id NOT IN (SELECT id FROM decklist)"
     "DELETE FROM recommendation WHERE id NOT IN (SELECT id FROM decklist)"
     "DELETE FROM owned_card WHERE card_id NOT IN (SELECT id FROM card)"]

    :rebuild
    {"tab_state"
     "id BLOB PRIMARY KEY NOT NULL,
      admin_selected BLOB,
      admin_status TEXT,
      card_page INT,
      card_search TEXT,
      user_id BLOB,
      FOREIGN KEY(user_id) REFERENCES user(id)"

     "printing"
     "id TEXT PRIMARY KEY NOT NULL,
      card_id TEXT NOT NULL,
      card_set_id TEXT NOT NULL,
      collector_number TEXT NOT NULL,
      external_uid TEXT,
      variant TEXT,
      FOREIGN KEY(card_id) REFERENCES card(id),
      FOREIGN KEY(card_set_id) REFERENCES card_set(id)"

     "card_aspect"
     "id TEXT PRIMARY KEY NOT NULL,
      aspect TEXT NOT NULL,
      card_id TEXT NOT NULL,
      FOREIGN KEY(card_id) REFERENCES card(id)"

     "meta"
     "id TEXT PRIMARY KEY NOT NULL,
      is_current INT NOT NULL,
      start_date TEXT NOT NULL,
      end_date TEXT,
      set_id TEXT,
      FOREIGN KEY(set_id) REFERENCES card_set(id)"

     "decklist"
     "id TEXT PRIMARY KEY NOT NULL,
      tournament_id TEXT NOT NULL,
      base_id TEXT,
      format TEXT,
      leader_id TEXT,
      placement INT,
      player_name TEXT,
      FOREIGN KEY(base_id) REFERENCES card(id),
      FOREIGN KEY(leader_id) REFERENCES card(id),
      FOREIGN KEY(tournament_id) REFERENCES tournament(id)"

     "deck_card"
     "id TEXT PRIMARY KEY NOT NULL,
      card_id TEXT NOT NULL,
      decklist_id TEXT NOT NULL,
      quantity INT NOT NULL,
      FOREIGN KEY(card_id) REFERENCES card(id),
      FOREIGN KEY(decklist_id) REFERENCES decklist(id)"

     "owned_card"
     "id BLOB PRIMARY KEY NOT NULL,
      card_id TEXT NOT NULL,
      quantity INT NOT NULL,
      user_id BLOB NOT NULL,
      FOREIGN KEY(card_id) REFERENCES card(id),
      FOREIGN KEY(user_id) REFERENCES user(id),
      UNIQUE(user_id, card_id)"}}

   ;; 2: Owned cards with a quantity of 0 are deleted, since setting a card's
   ;; quantity to 0 now deletes its row.
   {:cleanup ["DELETE FROM owned_card WHERE quantity = 0"]
    :rebuild {}}])

(defn- user-version [conn]
  (-> (jdbc/execute-one! conn ["PRAGMA user_version"]) vals first))

(defn- set-user-version! [conn version]
  (jdbc/execute! conn [(str "PRAGMA user_version = " (long version))]))

;; See https://www.sqlite.org/lang_altertable.html#otheralter. Indexes are
;; dropped with the old table; sqldef recreates them. Old columns that aren't in
;; the new table (e.g. ones sqldef declined to drop) are dropped too.
(defn- column-names [conn table]
  (mapv :name (jdbc/execute! conn
                             [(str "PRAGMA table_info(" table ")")]
                             {:builder-fn rs/as-unqualified-maps})))

(defn- rebuild-table! [conn table definitions]
  (let [new-table (str "new_" table)
        _         (jdbc/execute! conn [(str "CREATE TABLE " new-table " (\n"
                                            definitions
                                            "\n) STRICT")])
        columns   (->> (column-names conn new-table)
                       (filterv (set (column-names conn table)))
                       (str/join ", "))]
    (jdbc/execute! conn [(str "INSERT INTO " new-table " (" columns ") "
                              "SELECT " columns " FROM " table)])
    (jdbc/execute! conn [(str "DROP TABLE " table)])
    (jdbc/execute! conn [(str "ALTER TABLE " new-table " RENAME TO " table)])))

(defn- run-migration! [conn version {:keys [cleanup rebuild]}]
  (log/info "Running migration" version)
  (jdbc/with-transaction [tx conn]
    (doseq [sql cleanup]
      (jdbc/execute! tx [sql]))
    (doseq [[table definitions] rebuild]
      (rebuild-table! tx table definitions))
    (when-some [violations (not-empty (jdbc/execute! tx ["PRAGMA foreign_key_check"]))]
      (throw (ex-info "Migration left foreign key violations"
                      {:version version :violations violations})))
    (set-user-version! tx version)))

(defn migrate!
  "Runs pending migrations if the DB already exists; otherwise creates it at
  the latest version for sqldef to fill in."
  [{:biff.sqlite/keys [db-path] :as ctx}]
  (let [exists (.exists (io/file db-path))]
    (io/make-parents db-path)
    (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" db-path)})]
      (if-not exists
        (set-user-version! conn (count migrations))
        (do
          ;; Tables can only be rebuilt with foreign keys off, and the pragma
          ;; is a no-op inside a transaction.
          (jdbc/execute! conn ["PRAGMA foreign_keys = OFF"])
          (doseq [version (range (inc (user-version conn)) (inc (count migrations)))]
            (run-migration! conn version (nth migrations (dec version))))))))
  ctx)

(def module
  {:biff.core/id    ::module
   :biff.core/start migrate!})
