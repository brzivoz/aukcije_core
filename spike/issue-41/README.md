# Issue #41 — automatic RGZ parcel-access evidence

This directory retains the sanitized decision evidence and offline verifier for
[issue #41](https://github.com/brzivoz/aukcije_core/issues/41).

The selected result is
`OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION`. Issue #41
records the access decision; #21 implements the automatic `PARCEL_PATH`.
Fetching begins automatically in manual and scheduled refreshes after an
operator enables it with current dataset, capabilities, and schema pins. The
owner explicitly deferred publisher billing/service-agreement work and
operator monitoring. Published RGZ sources do not independently confirm the
automation/cache authority. The 2026-09-08 recheck succeeded, including the
object schema; dated hashes remain evidence rather than runtime defaults.
#42 stays open pending verification of its candidate join, footprint geometry,
whitelist, dataset, and access scope; the old timeout-based closure is withdrawn.

The full decision is
[`documentation/2026-09-03-decision-41-rgz-automatic-geometry-access.md`](../../documentation/2026-09-03-decision-41-rgz-automatic-geometry-access.md).

## Evidence

| Path | Purpose |
|---|---|
| `fixtures/reviewed-sources.json` | Exact URLs, read dates, published terms/tariff findings, and connection outcomes |
| `fixtures/sanitized-wfs-evidence.json` | Nine-type inventory, selected parcel schema fingerprint/allowlist, and building disposition |
| `fixtures/automated-access-contract.json` | Explicit activation plus rate, retry, timeout, cache, kill-switch, and deferral contract |
| `downstream-issue-21.md` | Automatic parcel resolver contract implemented by this change |
| `downstream-issue-42.md` | Building-contract review pending; fresh schema candidates and remaining checks |
| `verify.py` | Offline contract, source, redaction, configuration, migration, and documentation checks |

Raw XML, raw GeoJSON, coordinates, credentials, cookies, browser storage,
session state, personal records, and response bodies are not committed here.

## Verify offline

```bash
python3 spike/issue-41/verify.py
python3 -O spike/issue-41/verify.py
```

Both commands are network-free and require only Python's standard library.
