# Docker Compose stack

Runs the whole tournament platform: a Velocity proxy with the tournament
plugin, PostgreSQL (persistence) and Redis (Velocity <-> Paper messaging).
Temporary match servers are started on demand by the plugin as containers of
the `tournament-match:latest` template image, on the `tournament` network.

## Layout

| Path                    | Purpose                                          |
| ----------------------- | ------------------------------------------------ |
| `docker-compose.yml`    | redis + postgres + velocity services             |
| `velocity/`             | Docker context of the proxy image                |
| `match/`                | Docker context of the match template image       |
| `build.sh`              | `mvn package`, stages jars, builds and starts    |

## Quick start

```bash
# Linux, with Docker + Maven + JDK 21/25 available
./docker/build.sh
```

The proxy listens on `localhost:25577`. Create teams and start the tournament
with `/team create <name>`, `/tournament start` (permissions `tournament.*`).
Matches are provisioned as containers automatically and the next round starts
when all matches of the current round have reported their result.

## How a match starts

1. The velocity plugin starts a container `game-<matchId8>` from
   `tournament-match:latest` on the `tournament` network
   (`docker run` via the `docker` CLI).
2. Environment variables (`TOURNAMENT_SERVER_ID`, `REDIS_HOST/PORT`,
   `REDIS_PASSWORD`) tell the Paper plugin who it is and where Redis lives.
3. The plugin waits for the container IP (`docker inspect`), registers it in
   Velocity and publishes the match instructions on Redis.
4. Paper acknowledges readiness, Velocity transfers the players, the match
   runs (TNT wars kit, eliminations, timeout).
5. Paper publishes the result; Velocity validates and records it; the match
   container is stopped and removed, the server unregistered.

## Configuration

- Velocity plugin config: `velocity/plugins/tournament/config.yml` — Redis and
  PostgreSQL hostnames are the compose service names; `server.docker.enabled`
  must stay `true` here.
- Proxy settings: `velocity/velocity.toml` — bind, `forwarding-secret`.
- Match settings: `match/plugins/tournament/config.yml` (arena world, spawns,
  explosion, timeout) and `match/config/paper-global.yml` (velocity forwarding
  secret — must match `velocity.toml`).
- The arena world is generated as a void world with obsidian pads at the
  configured spawns if it does not exist; drop a real world into the image
  (e.g. `match/world/`) to use custom terrain.

## Updating versions

Paper and Velocity jars are pinned to the builds the plugins are compiled
against. Bump them with `docker build --build-arg PAPER_URL=...` /
`--build-arg VELOCITY_URL=...` (URLs from
`https://fill.papermc.io/v3/projects/paper` or `.../velocity`), and update
the `paper-api`/`velocity-api` versions in the parent `pom.xml` first.

## Local development

Run the stack without Docker provisioning (no daemon needed):

```bash
# edit velocity/plugins/tournament/config.yml: server.docker.enabled=false
docker compose -f docker/docker-compose.yml up --build
```

Matches are then "simulated" (no containers): the provisioning flow and the
queue are exercised, but no Paper server acknowledges the match, so a match
stays RUNNING until a real server reports its result. Use this mode to test
team/tournament commands and persistence against PostgreSQL.

## Cleanup

```bash
docker compose -f docker/docker-compose.yml down        # keep volumes
docker compose -f docker/docker-compose.yml down -v     # wipe postgres data
```
