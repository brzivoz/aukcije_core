# Local basemap serving operations

Issue #25 serves one checksum-validated immutable bundle from #24 through the
application origin. Generated map data stays below ignored `data/basemap/`; Git
contains the serving code, browser dependencies, runbook, and compact test
fixture, not the 232 MB Serbia archive.

## Runtime layout

```text
data/basemap/
  ACTIVE                                      one build id plus newline
  .activation.lock                            activation serialization only
  builds/
    serbia-2026-08-01-e82bacf6e754/
      build-manifest.json
      serbia.pmtiles
      style.json
      sprites/...
      glyphs/...
      licenses/...
```

`ACTIVE` is a regular file, never a symlink. Bundle directories are immutable:
do not edit, replace, or delete their files after publication. The application
validates the pointer at startup and polls it (one second by default) while
running. A newly selected build is hashed in the background; request threads
continue using the previous in-memory snapshot until every manifest entry and
runtime contract passes.

Set `BASEMAP_ASSET_DIRECTORY` when the deployment does not use
`data/basemap`. `BASEMAP_ASSET_POLL_INTERVAL` accepts a Java duration such as
`PT2S`.

## Activate a new build without downtime

First produce or revalidate the immutable bundle:

```bash
./basemap/build.sh
```

Then activate the exact build id printed by that command:

```bash
./gradlew activateBasemap --args='--version serbia-2026-08-01-e82bacf6e754'
```

For a non-default root:

```bash
./gradlew activateBasemap \
  --args='--directory /srv/aukcije/basemap --version serbia-2026-08-01-e82bacf6e754'
```

The command takes the activation lock, validates the complete manifest
inventory, hashes every recorded file, checks the PMTiles v3 header, rejects
symlinks/path escapes, verifies same-origin style URLs and linked OpenStreetMap
attribution, fsyncs a temporary pointer, and replaces `ACTIVE` with an atomic
filesystem move. If any step fails, `ACTIVE` is untouched.

The running application notices the new pointer automatically. It retains the
last good snapshot during validation and swaps its in-memory reference only
after the candidate passes. No restart or serving gap is required.

## Verify health and HTTP behavior

The sanitized status endpoint returns `200` for an available last-good bundle
and `503` when no valid bundle has ever loaded:

```bash
curl --fail-with-body http://localhost:8081/api/basemap/status | jq
```

Check `healthy`, `activeVersion`, `pointerVersion`, `artifactSha256`,
`artifactSizeBytes`, and `warning`. A rejected new pointer can therefore show a
healthy old `activeVersion`, the attempted `pointerVersion`, and
`ACTIVE_POINTER_REJECTED` without exposing filesystem paths.

Exercise the PMTiles range contract directly:

```bash
curl -I http://localhost:8081/basemap/serbia.pmtiles
curl -i -H 'Range: bytes=0-16383' \
  http://localhost:8081/basemap/serbia.pmtiles -o /dev/null
```

The archive responds with `Accept-Ranges: bytes`, a stable strong
`"sha256-..."` ETag, `application/vnd.pmtiles`, conditional cache policy, and
`X-Basemap-Version`. Valid single prefix, open-ended, and suffix ranges return
`206` plus `Content-Range`; malformed, multiple, reversed, or unsatisfiable
ranges return `416` plus `Content-Range: bytes */<size>`. A matching
`If-None-Match` returns `304`; a stale `If-Range` deliberately falls back to a
complete `200` response.

Open <http://localhost:8081/basemap-smoke.html> for the operator render. It uses
the same pinned, same-origin MapLibre/PMTiles code as the browser regression and
keeps the linked `© OpenStreetMap contributors` attribution visible. It is a
basemap diagnostic, not the auction-map UX owned by #27.

## Roll back without downtime

Keep at least one previously verified build until the new version has completed
its observation window. Rollback is the same validated atomic operation:

```bash
./gradlew activateBasemap --args='--version <previous-build-id>'
curl --fail-with-body http://localhost:8081/api/basemap/status | jq
```

Wait until `activeVersion` equals the previous build. Existing requests that
already opened the newer immutable file finish against that file; subsequent
requests use the rolled-back snapshot.

## Failure recovery and retirement

- Activation failure: read the command error, repair by producing a new
  immutable build, and rerun. Never edit the rejected directory in place.
- `ACTIVE_POINTER_REJECTED`: the application is still serving its last good
  snapshot. Run the activation command for a known-good build to repair the
  on-disk pointer.
- `ACTIVE_POINTER_MISSING` with a healthy response: restore a known-good pointer
  with the activation command before restarting the application.
- `503 UNAVAILABLE`: no valid snapshot has loaded. Activate a known-good build;
  the watcher will recover without an application restart.
- Retire a build only after health shows it is not active, the rollback window
  has elapsed, and no rollback procedure names it. Remove only that exact
  `data/basemap/builds/<id>/` directory. #24 can regenerate it from its pins.

Browser dependency upgrades are separate from bundle activation. Follow
`documentation/BROWSER_AND_FRONTEND.md`: update one exact version, license,
file hash, `static/vendor/frontend-assets.lock.json`, imports, and the complete
browser suite in the same change.
