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
  price_cents INT,
  type TEXT
) STRICT;

CREATE TABLE card_aspect (
  id TEXT PRIMARY KEY NOT NULL,
  aspect TEXT NOT NULL,
  card_id TEXT NOT NULL
) STRICT;

CREATE TABLE card_set (
  id TEXT PRIMARY KEY NOT NULL,
  number INT,
  published_on TEXT
) STRICT;

CREATE TABLE deck_card (
  id TEXT PRIMARY KEY NOT NULL,
  card_id TEXT NOT NULL,
  decklist_id TEXT NOT NULL,
  quantity INT NOT NULL
) STRICT;

CREATE TABLE decklist (
  id TEXT PRIMARY KEY NOT NULL,
  tournament_id TEXT NOT NULL,
  base_id TEXT,
  format TEXT,
  leader_id TEXT,
  placement INT,
  player_name TEXT
) STRICT;

CREATE TABLE meta (
  id TEXT PRIMARY KEY NOT NULL,
  is_current INT NOT NULL,
  start_date TEXT NOT NULL,
  end_date TEXT,
  set_id TEXT
) STRICT;

CREATE TABLE printing (
  id TEXT PRIMARY KEY NOT NULL,
  card_id TEXT NOT NULL,
  card_set_id TEXT NOT NULL,
  collector_number TEXT NOT NULL,
  external_uid TEXT,
  variant TEXT
) STRICT;

CREATE TABLE recommendation (
  id TEXT PRIMARY KEY NOT NULL,
  rank INT NOT NULL
) STRICT;

CREATE TABLE sync_state (
  id TEXT PRIMARY KEY NOT NULL,
  attempted_at TEXT,
  watermark TEXT
) STRICT;

CREATE TABLE tab_state (
  id BLOB PRIMARY KEY NOT NULL,
  admin_selected BLOB,
  admin_status TEXT,
  card_page INT,
  card_search TEXT,
  user_id BLOB
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
  seen_recommendations BLOB,
  unsubscribed_at INT,
  UNIQUE(email)
) STRICT;

CREATE TABLE notification (
  id BLOB PRIMARY KEY NOT NULL,
  created_at INT NOT NULL,
  token BLOB NOT NULL,
  user_id BLOB NOT NULL,
  FOREIGN KEY(user_id) REFERENCES user(id),
  UNIQUE(token)
) STRICT;

CREATE TABLE owned_card (
  id BLOB PRIMARY KEY NOT NULL,
  card_id TEXT NOT NULL,
  quantity INT NOT NULL,
  user_id BLOB NOT NULL,
  FOREIGN KEY(user_id) REFERENCES user(id),
  UNIQUE(user_id, card_id)
) STRICT;

CREATE INDEX idx_card_aspect_card_id ON card_aspect(card_id);
CREATE INDEX idx_deck_card_decklist_id ON deck_card(decklist_id);
CREATE INDEX idx_decklist_tournament_id ON decklist(tournament_id);
CREATE INDEX idx_notification_created_at ON notification(created_at);
CREATE INDEX idx_notification_user_id ON notification(user_id);
CREATE INDEX idx_printing_card_id ON printing(card_id);
CREATE INDEX idx_printing_collector_number ON printing(collector_number);
CREATE INDEX idx_tab_state_user_id ON tab_state(user_id);
CREATE INDEX idx_user_joined_at ON user(joined_at);