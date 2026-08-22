# Database operations

Issue #15 replaces the H2 proof-of-concept runtime with PostgreSQL 18, PostGIS
3.6, and Flyway. Auction rows are derived from eAukcija, so the one-time H2
transition is a clean source re-sync, not a cross-engine data conversion.

## Configuration contract

| Profile | Database | Schema policy | Credentials |
|---|---|---|---|
| `dev` | local/explicit PostgreSQL | Flyway + Hibernate `validate` | environment plus ignored secret file |
| `test` | Testcontainers PostGIS | Flyway + Hibernate `validate` | ephemeral container connection |
| `prod` | explicitly configured PostgreSQL | Flyway + Hibernate `validate` | deployment secret injection; no defaults |
| `local-h2` | legacy H2 only | isolated `update`; Flyway off | compatibility after archive only |

Dev accepts `AUKCIJE_DB_HOST`, `AUKCIJE_DB_PORT`, `AUKCIJE_DB_NAME`,
`AUKCIJE_DB_USER`, and the required `AUKCIJE_DB_PASSWORD`. Prod requires
`AUKCIJE_DB_URL`, `AUKCIJE_DB_USER`, and `AUKCIJE_DB_PASSWORD`. No password is
committed. Compose reads its database password from
`.secrets/postgres-password` by default; `.secrets/`, `.env*`, dumps, and old
`data/` files are ignored.

`.env` is Compose interpolation only; Spring Boot does not load it. When a
Compose setting differs from the dev defaults, export the matching
`AUKCIJE_DB_*` value into the application process as well. The application
requires exactly one explicit database profile (`dev`, `test`, `prod`, or
`local-h2`) before datasource initialization, preventing an accidental prod
startup with dev connection defaults.

The Compose port is bound only to `127.0.0.1`. Its image is both tag- and
digest-pinned, the named volume is mounted at PostgreSQL 18's
`/var/lib/postgresql` parent path, and the service has a healthcheck, CPU,
memory, and shared-memory limits.

The pinned PostGIS digest currently publishes an amd64 image. Docker Desktop on
Apple Silicon therefore warns about a platform mismatch and runs the database
under emulation; this is expected until a reviewed multi-architecture digest is
adopted.

## First clean start

Generate a strong local-only password with a password manager and put that one
line in the ignored `.secrets/postgres-password` file. Then:

```bash
docker compose up -d --wait db
AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)" \
  ./gradlew bootRun
```

The Gradle `bootRun` task selects `dev` for local ergonomics. This task-level
argument is not packaged into the application; jar/deployment startup still
requires an explicit database profile and fails before context creation when it
is missing or ambiguous.

Flyway creates PostGIS and the auction baseline on an empty database. Startup
must stop on an unavailable database, bad credentials, unavailable/missing
PostGIS, a migration checksum mismatch, or Hibernate schema drift. Do not work
around these failures with `ddl-auto=update`, `flyway repair`, or manual schema
edits; diagnose and review the mismatch first.

## Archive the legacy H2 file

Application startup checks `data/aukcije.mv.db` and `data/aukcije.h2.db` (or
the path in `AUKCIJE_LEGACY_H2_PATH`) before Spring starts. If present, it logs
a warning and leaves the file untouched.

Stop the old application, then archive rather than move or delete the file:

```bash
mkdir -p backups
export AUKCIJE_H2_BACKUP="backups/aukcije-h2-$(date -u +%Y%m%dT%H%M%SZ).mv.db"
cp -p data/aukcije.mv.db "$AUKCIJE_H2_BACKUP"
shasum -a 256 data/aukcije.mv.db "$AUKCIJE_H2_BACKUP"
```

If the historical filename is `aukcije.h2.db`, substitute that exact name.
Keep the archive until the PostgreSQL re-sync and spot checks are accepted.
The application never deletes either file.

Only after verifying the archive, the compatibility profile can open H2 without
changing the normal runtime:

```bash
./gradlew bootRun --args='--spring.profiles.active=local-h2'
```

This profile intentionally retains legacy `ddl-auto=update`, so it can modify
the file's schema. Point `AUKCIJE_H2_URL` at a copied archive when preservation
of the original is required.

## Backup and restore verification

Set non-secret names to match `.env` if customized:

```bash
export AUKCIJE_DB_NAME="${AUKCIJE_DB_NAME:-aukcije}"
export AUKCIJE_DB_USER="${AUKCIJE_DB_USER:-aukcije}"
mkdir -p backups
export AUKCIJE_BACKUP="backups/aukcije-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T db pg_dump \
  --username="$AUKCIJE_DB_USER" --dbname="$AUKCIJE_DB_NAME" \
  --format=custom > "$AUKCIJE_BACKUP"
test -s "$AUKCIJE_BACKUP"
docker compose exec -T db pg_restore --list < "$AUKCIJE_BACKUP"
```

Exercise the backup against a disposable database before relying on it:

```bash
export AUKCIJE_RESTORE_CHECK=aukcije_restore_check
docker compose exec -T db createdb \
  --username="$AUKCIJE_DB_USER" "$AUKCIJE_RESTORE_CHECK"
docker compose exec -T db pg_restore \
  --username="$AUKCIJE_DB_USER" --dbname="$AUKCIJE_RESTORE_CHECK" \
  --exit-on-error < "$AUKCIJE_BACKUP"
docker compose exec -T db psql \
  --username="$AUKCIJE_DB_USER" --dbname="$AUKCIJE_RESTORE_CHECK" \
  --set=ON_ERROR_STOP=1 \
  --command='SELECT count(*) AS auctions FROM auctions; SELECT PostGIS_Version();'
docker compose exec -T db dropdb \
  --username="$AUKCIJE_DB_USER" --force "$AUKCIJE_RESTORE_CHECK"
```

For a real restore, stop the application, retain the broken database/volume,
create a new empty target database, restore with `--exit-on-error`, and start
the application against that target. Flyway and Hibernate then validate the
restored history and schema before traffic is served.

## Clean re-sync

Take and verify a PostgreSQL backup first. With the application stopped, clear
only the derived auction rows; do not remove the named volume or Flyway history:

```bash
docker compose exec -T db psql \
  --username="$AUKCIJE_DB_USER" --dbname="$AUKCIJE_DB_NAME" \
  --set=ON_ERROR_STOP=1 --command='TRUNCATE TABLE auctions;'
```

Start the application, trigger listings and then details, and inspect status:

```bash
curl --fail-with-body --request POST http://localhost:8081/api/sync/listings
curl --fail-with-body http://localhost:8081/api/sync/status
curl --fail-with-body --request POST http://localhost:8081/api/sync/details
curl --fail-with-body http://localhost:8081/api/sync/status
```

Do not delete the H2 archive after a merely successful HTTP response. Compare
the PostgreSQL row count, distinct source IDs, representative Serbian text,
prices, timestamps, municipality/category facets, and detail count. The
automated repository fixture test establishes the current baseline of 86 source
rows collapsing to 83 stable auction IDs.

## Shutdown and removal

Stop services without removing persisted data:

```bash
docker compose down
```

`docker compose down --volumes` destroys the PostgreSQL data volume and is not
part of normal operation or the H2 transition.
