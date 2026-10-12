-- Auto-generated; do not edit.

CREATE TABLE biff_sqlite_kv (
  id BLOB PRIMARY KEY NOT NULL,
  k TEXT NOT NULL,
  namespace TEXT NOT NULL,
  v BLOB NOT NULL,
  UNIQUE(namespace, k)
) STRICT;

CREATE TABLE card (
  id TEXT PRIMARY KEY NOT NULL,
  collector_number TEXT NOT NULL,
  name TEXT NOT NULL,
  set_code TEXT,
  type TEXT,
  variant TEXT
) STRICT;

CREATE TABLE card_aspect (
  id TEXT PRIMARY KEY NOT NULL,
  aspect TEXT NOT NULL,
  card_id TEXT NOT NULL
) STRICT;

CREATE TABLE card_price (
  id TEXT PRIMARY KEY NOT NULL,
  cents INT NOT NULL,
  product_id INT NOT NULL
) STRICT;

CREATE TABLE deck_card (
  id TEXT PRIMARY KEY NOT NULL,
  collector_number TEXT NOT NULL,
  decklist_id TEXT NOT NULL,
  quantity INT NOT NULL
) STRICT;

CREATE TABLE decklist (
  id TEXT PRIMARY KEY NOT NULL,
  tournament_id TEXT NOT NULL,
  base_name TEXT,
  base_number TEXT,
  format TEXT,
  leader_name TEXT,
  melee_id TEXT,
  player_name TEXT
) STRICT;

CREATE TABLE meta (
  id TEXT PRIMARY KEY NOT NULL,
  is_current INT NOT NULL,
  start_date TEXT NOT NULL,
  end_date TEXT
) STRICT;

CREATE TABLE recommendation (
  id TEXT PRIMARY KEY NOT NULL,
  rank INT NOT NULL
) STRICT;

CREATE TABLE standing (
  id TEXT PRIMARY KEY NOT NULL,
  tournament_id TEXT NOT NULL,
  decklist_id TEXT,
  final_placement INT
) STRICT;

CREATE TABLE sync_state (
  id TEXT PRIMARY KEY NOT NULL,
  attempted_at TEXT,
  watermark TEXT
) STRICT;

CREATE TABLE tab_state (
  id BLOB PRIMARY KEY NOT NULL,
  data BLOB
) STRICT;

CREATE TABLE tournament (
  id TEXT PRIMARY KEY NOT NULL,
  held_at TEXT NOT NULL,
  name TEXT NOT NULL,
  format TEXT,
  player_count INT
) STRICT;

CREATE TABLE user (
  id BLOB PRIMARY KEY NOT NULL,
  email TEXT NOT NULL,
  joined_at INT NOT NULL,
  display_name TEXT,
  UNIQUE(email)
) STRICT;

CREATE INDEX idx_card_collector_number ON card(collector_number);
CREATE INDEX idx_card_aspect_card_id ON card_aspect(card_id);
CREATE INDEX idx_deck_card_decklist_id ON deck_card(decklist_id);
CREATE INDEX idx_decklist_tournament_id ON decklist(tournament_id);
CREATE INDEX idx_standing_decklist_id ON standing(decklist_id);
CREATE INDEX idx_user_joined_at ON user(joined_at);