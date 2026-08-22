# Issue #15 verification — 2026-08-22

Scope: local implementation based on `main` at `d91cd89`. No commit, push, or
remote CI run was requested; this document records fresh local evidence only.

## Automated verification

Command:

```bash
./gradlew clean test --no-daemon
```

Result: `BUILD SUCCESSFUL` in 29 seconds. JUnit XML reports contain 51 tests,
zero failures, and zero skipped tests. The suite used the digest-pinned
`postgis/postgis:18-3.6` image with PostgreSQL 18.6 and PostGIS 3.6.

The acceptance coverage includes:

- empty Flyway migration through V3 and a V1-to-current upgrade fixture;
- Hibernate `ddl-auto=validate` and a real JPA repository round-trip;
- PostGIS version/function and CRS/spatial-query checks;
- five Flyway-owned indexes for the existing municipality, place, category,
  status, and starting-price query paths;
- complete application restart with persisted data;
- custom-format `pg_dump`/`pg_restore` into a clean database;
- 86 fixture rows deduplicated to 83 stable auction IDs, fixture-derived facet
  set parity plus exact PostgreSQL facet ordering, the controller-equivalent
  paged municipality/price/Cyrillic search specification, clean reload, and 12
  simultaneous `ON CONFLICT` writers producing one row;
- invalid migration, unavailable PostGIS, removed PostGIS after migration,
  schema drift, checksum drift, bad credentials, and unavailable database;
- a real servlet startup proving removed PostGIS fails context refresh before
  `WebServerInitializedEvent`, so no connector can accept traffic;
- dev/test/prod secret/schema-policy contracts, isolated legacy-H2 behavior,
  rejection of missing/ambiguous database profiles, and proof that the legacy
  warning does not modify the file.

Flyway 13.2.0 was explicitly rejected during implementation because its removed
configuration API is binary-incompatible with Spring Boot 3.4.3. Flyway 11.20.2
passes the full Java 17/Spring Boot integration suite and recognizes PostgreSQL
18.6 without the earlier version-support warning. Redgate currently lists
PostgreSQL 18 as verified and documents the separate PostgreSQL database module:
<https://documentation.red-gate.com/fd/postgresql-database-277579325.html>.

## Fresh Compose/runtime verification

The full lifecycle proof used isolated Compose project
`aukcije-core-issue15-review` on loopback port `55433`. A second disposable
project, `aukcije-core-bootrun-review` on port `55434`, verified the final local
startup ergonomics. Both used ignored, verification-only secrets.

1. `docker compose ... config --quiet` succeeded.
2. `docker compose ... up -d --wait db` created a named volume and reached
   `healthy`.
3. Plain `./gradlew bootRun` received only `dev` from the Gradle task, migrated
   the empty database through V1/V2/V3, enabled Hibernate Spatial, validated the
   entity schema, completed the PostGIS bean preflight before the connector
   opened, and served `/` with HTTP 200. An explicit `--args` invocation also
   overrode that task default with only `local-h2`, using a disposable in-memory
   database; the packaged application retains no default profile.
4. The startup preflight detected the existing `data/aukcije.mv.db`, emitted
   the clean-re-sync/archive warning, and did not change or remove the file.
5. Database inspection returned all three Flyway scripts, all five filter
   indexes plus the primary-key index, and `PostGIS_Version()` 3.6.
6. A disposable auction row remained present after restarting the database
   container; the running application recovered and again returned HTTP 200.
7. A custom-format backup restored into a disposable database; the restored
   row, three successful Flyway history rows, five filter indexes, and PostGIS
   3.6 were verified.
8. The restore-check database, isolated container/network/volume, and
   verification secret were removed. The user's legacy H2 file was untouched.

No live eAukcija sync was triggered: repository parity and clean re-sync use the
committed fixture so verification does not depend on or write to an external
service.

The pinned PostGIS manifest is currently amd64-only. Docker Desktop reported
the expected platform mismatch on this Apple Silicon host and ran it under
emulation; the same digest is used by Compose, Testcontainers, and CI.
