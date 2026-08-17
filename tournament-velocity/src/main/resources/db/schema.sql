-- Schéma PostgreSQL de l'historique des tournois (Phase 6).
-- Chaque enregistrement du réacteur (tournoi) est persisté comme un arbre
-- complet : équipes/joueurs, rounds, matches, résultats et statistiques.
-- Types portables PostgreSQL/H2 : TIMESTAMP WITH TIME ZONE au lieu de TIMESTAMPTZ.

CREATE TABLE IF NOT EXISTS tournaments (
    id              UUID PRIMARY KEY,
    name            TEXT NOT NULL,
    state           TEXT NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE,
    finished_at     TIMESTAMP WITH TIME ZONE,
    champion_team_id UUID
);

CREATE TABLE IF NOT EXISTS teams (
    id           UUID PRIMARY KEY,
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    captain_id   UUID NOT NULL
);

CREATE TABLE IF NOT EXISTS team_players (
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    team_id       UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    player_id     UUID NOT NULL,
    username      TEXT NOT NULL,
    role          TEXT NOT NULL,
    PRIMARY KEY (tournament_id, player_id)
);

CREATE TABLE IF NOT EXISTS rounds (
    id            UUID PRIMARY KEY,
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    number        INT NOT NULL,
    state         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS matches (
    id            UUID PRIMARY KEY,
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    round_id      UUID NOT NULL REFERENCES rounds(id) ON DELETE CASCADE,
    server_id     TEXT,
    status        TEXT NOT NULL,
    duration_ms   BIGINT,
    finished_at   TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS match_team_results (
    match_id       UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    team_id        UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    placement      TEXT NOT NULL,
    placement_rank INT NOT NULL,
    kills          BIGINT NOT NULL,
    deaths         BIGINT NOT NULL,
    PRIMARY KEY (match_id, team_id)
);

CREATE TABLE IF NOT EXISTS match_player_stats (
    match_id   UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    player_id  UUID NOT NULL,
    team_id    UUID NOT NULL,
    kills      BIGINT NOT NULL,
    deaths     BIGINT NOT NULL,
    assists    BIGINT NOT NULL,
    damage     BIGINT NOT NULL,
    PRIMARY KEY (match_id, player_id)
);
