# Tournament Platform Docker Stack

Runs the whole tournament platform: a Velocity proxy with the tournament
plugin, PostgreSQL (persistence), Redis (Velocity ↔ Paper messaging), and a
persistent Lobby server. Temporary match servers are started on demand by the
plugin as containers from the `tournament-gameserver:latest` template image.

## Layout

```
tntwarsgame/
├── templates/               # Reusable template images (build once, deploy many)
│   ├── proxy/               # Velocity proxy template
│   │   ├── Dockerfile
│   │   ├── velocity.toml
│   │   └── plugins/tournament/config.yml
│   ├── gameserver/          # Match/game server template (Paper)
│   │   ├── Dockerfile
│   │   ├── server.properties
│   │   ├── eula.txt
│   │   ├── config/paper-global.yml
│   │   └── plugins/tournament/config.yml
│   └── lobby/               # Persistent lobby server template
│       ├── Dockerfile
│       ├── server.properties
│       ├── eula.txt
│       ├── config/paper-global.yml
│       └── plugins/
├── docker/
│   ├── docker-compose.yml   # Uses pre-built template images
│   ├── build.sh             # Builds all 3 template images (+ stages jars)
│   └── templates.env        # Optional: override image tags, ports
```

## Quick start

```bash
# Linux/macOS/WSL with Docker + Maven + JDK 21+
./docker/build.sh
docker compose -f docker/docker-compose.yml up -d
```

The proxy listens on **`localhost:29020`**. Create teams and start the tournament
with `/team create <name>`, `/tournament start` (permissions `tournament.*`).
Players connect to the lobby first; when the tournament starts they are
transferred to match servers automatically.

## How a match starts

1. Velocity plugin starts a container `game-<matchId8>` from
   `tournament-gameserver:latest` on the `tournament` network
   (`docker run` via the `docker` CLI).
2. Environment variables (`TOURNAMENT_SERVER_ID`, `REDIS_HOST/PORT`,
   `REDIS_PASSWORD`) tell the Paper plugin its identity and Redis location.
3. Plugin waits for container IP (`docker inspect`), registers it in Velocity,
   publishes match instructions on Redis.
4. Paper acknowledges readiness, Velocity transfers the players, the match
   runs (TNT wars kit, eliminations, timeout).
5. Paper publishes the result; Velocity validates and records it; the match
   container is stopped and removed, the server unregistered.

## Configuration

| File | Purpose |
|------|---------|
| `templates/proxy/plugins/tournament/config.yml` | Redis/PostgreSQL hostnames (compose service names), `server.docker.image: tournament-gameserver:latest`, `server.docker.enabled: true` |
| `templates/proxy/velocity.toml` | Bind address (`0.0.0.0:29020`), `forwarding-secret`, static `lobby` server entry |
| `templates/gameserver/plugins/tournament/config.yml` | Arena world, spawns, explosion, timeout |
| `templates/gameserver/config/paper-global.yml` | Velocity forwarding secret (must match `velocity.toml`) |
| `templates/lobby/config/paper-global.yml` | Same forwarding secret for lobby |

The arena world is generated as a void world with obsidian pads at the
configured spawns if it does not exist; drop a real world into the template
(e.g. `templates/gameserver/world/`) to use custom terrain.

## Updating versions

Paper and Velocity jars are pinned to the builds the plugins are compiled
against. Bump them with:
```bash
docker build --build-arg PAPER_URL=... templates/gameserver
docker build --build-arg PAPER_URL=... templates/lobby
docker build --build-arg VELOCITY_URL=... templates/proxy
```
(URLs from `https://fill.papermc.io/v3/projects/paper` or `.../velocity`), and
update the `paper-api`/`velocity-api` versions in the parent `pom.xml` first.

## Template versioning

`docker/build.sh` tags images with both `latest` and a version tag
(`<tag-suffix>-<git-sha>`). Use `--push` to push to a registry, `--tag` to
customize the suffix, `--no-cache` to force rebuild.

## Local development (no Docker daemon)

```bash
# Set server.docker.enabled=false in templates/proxy/plugins/tournament/config.yml
docker compose -f docker/docker-compose.yml up --build
```

Matches are "simulated" (no containers): the provisioning flow and queue are
exercised, but no Paper server acknowledges the match, so a match stays
RUNNING until a real server reports its result. Use this to test team/tournament
commands and persistence against PostgreSQL.

## Cleanup

```bash
docker compose -f docker/docker-compose.yml down        # keep volumes
docker compose -f docker/docker-compose.yml down -v     # wipe postgres data
```