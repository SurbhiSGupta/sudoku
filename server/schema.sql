-- One row per device per day's puzzle; the first solve is the one that counts
CREATE TABLE IF NOT EXISTS solves (
  game TEXT NOT NULL,
  date TEXT NOT NULL,
  player TEXT NOT NULL,
  level TEXT NOT NULL,
  seconds INTEGER NOT NULL,
  mistakes INTEGER NOT NULL,
  at INTEGER NOT NULL,
  PRIMARY KEY (game, date, player)
);
CREATE INDEX IF NOT EXISTS solves_by_day ON solves (game, date, seconds);
