# Deploying the ledger

Live at **https://ledger-75986543116.us-central1.run.app** — Swagger UI at `/swagger-ui/index.html`.
Google Cloud Run (project `ledger-demo-506601`, us-central1, 512MB, scale to zero) against Neon Postgres.
Free tier covers this: 2M requests, 180k vCPU-seconds and 360k GiB-seconds a month.

The app reads `DB_URL`, `DB_USER`, `DB_PASSWORD` and binds to `$PORT`. Flyway runs the migrations
on first boot, so the schema builds itself against an empty database.

## Redeploying
```
gcloud run deploy ledger --source . --region us-central1 --allow-unauthenticated --memory 512Mi
```
Cloud Build builds the image on Google's side, so the arm64/amd64 mismatch you get from building
on an Apple Silicon Mac never comes up.

On a fresh project the first deploy fails with `PERMISSION_DENIED ... default service account is
missing required IAM permissions`. Google tightened the Cloud Build defaults. Grant the compute
service account `roles/cloudbuild.builds.builder`, `roles/storage.objectViewer`,
`roles/artifactregistry.writer` and `roles/logging.logWriter`, then deploy again.

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

## Cold starts
Scale to zero, so the first request after idle pays for Spring starting up. Measured **3.3 seconds**
cold, 0.09-0.16s warm. The Dockerfile sets `-XX:+UseSerialGC -XX:TieredStopAtLevel=1
-Dspring.main.lazy-initialization=true`, which roughly halves it. Setting `--min-instances 1` would
remove it entirely but leaves the free tier.

## /healthz vs /health
Google's frontend reserves the exact path `/healthz` on Cloud Run and answers it with its own 404
before the request reaches the container. `/healthz/` with a trailing slash goes through, and so
does every other path. The controller maps both `/healthz` and `/health` so the endpoint is
reachable wherever it runs.

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
