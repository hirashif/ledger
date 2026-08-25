# Deploying the ledger

Live at **https://shifan-ledger.fly.dev** — Swagger UI at `/swagger-ui/index.html`.
Running on Fly.io (`shifan-ledger`, dfw, one shared-cpu-1x/512MB machine) against Neon Postgres.

The app reads `DB_URL`, `DB_USER`, `DB_PASSWORD` and binds to `$PORT`. Flyway runs the migrations
on first boot, so the schema builds itself against an empty database.

## Redeploying
```
fly deploy --remote-only
```
`--remote-only` matters: Fly's nodes are amd64, and building on an Apple Silicon Mac produces
arm64 images that fail to start once they're in the cluster.

## Database
Neon free tier. Two things to get right in the connection string:

- Use the **direct** endpoint, not the `-pooler` one. Flyway takes session-level advisory locks to
  serialize migrations, and Neon's pooler is PgBouncer in transaction mode, which doesn't hold them.
- Drop `channel_binding=require`. The Postgres JDBC driver doesn't negotiate it cleanly.

So the URL is `jdbc:postgresql://<direct-host>/<db>?sslmode=require`.

Secrets live in Fly, not in this repo:
```
fly secrets set DB_URL=... DB_USER=... DB_PASSWORD=...
```

## Why it's always on
`min_machines_running = 1` and `auto_stop_machines = false`. With scale-to-zero the machine stops
rather than suspends, and a cold hit measured **10.3 seconds** (full boot plus Spring startup plus
Neon waking). Warm responses are 0.1-0.3s. For a link that's meant to be clicked by someone reading
a resume, ten seconds reads as broken, so it stays warm. Costs a few dollars a month.

## Seeding a fresh database
An empty ledger is a bad demo. Three accounts and two transfers:
```
curl -X POST $URL/accounts -H 'Content-Type: application/json' -d '{"name":"treasury","currency":"USD","allowsOverdraft":true}'
curl -X POST $URL/accounts -H 'Content-Type: application/json' -d '{"name":"alice","currency":"USD"}'
curl -X POST $URL/accounts -H 'Content-Type: application/json' -d '{"name":"bob","currency":"USD"}'

curl -X POST $URL/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: seed-fund-alice' \
  -d '{"description":"opening balance for alice","entries":[{"accountId":1,"amountMinor":-50000},{"accountId":2,"amountMinor":50000}]}'

curl -X POST $URL/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: seed-alice-bob-1' \
  -d '{"description":"alice pays bob","entries":[{"accountId":2,"amountMinor":-2500},{"accountId":3,"amountMinor":2500}]}'
```
Replay that last one with the same `Idempotency-Key` and it returns the same `transactionId`
instead of moving the money twice.
