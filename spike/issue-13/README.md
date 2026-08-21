# Issue #13 - RGZ parcel-access decision evidence

This directory contains the small, offline-verifiable evidence bundle for
[issue #13](https://github.com/brzivoz/aukcije_core/issues/13).

The deliverable is
`documentation/2026-08-21-decision-13-rgz-parcel-access.md`. Outcome C is
selected: RGZ parcel geometry is unavailable to production until explicit
reuse authority exists.

## Contents

| Path | Purpose |
|---|---|
| `fixtures/guest-map-observation.json` | Whitelisted guest UI/WMS observation; viewport and bodies redacted |
| `fixtures/capabilities-parcel-layer.json` | Sanitized WMS capabilities response subset and raw-response hash |
| `fixtures/ko-parcel-samples.json` | Three #32 samples with expected CRS transformations |
| `fixtures/unavailable-contract.json` | Machine-readable outcome-C product contract |
| `downstream-issue-21.md` | Exact replacement body for GitHub issue #21 |
| `verify.py` | Offline fixture, redaction, decision, and CRS checks |

## Verify

```bash
python3 -m venv /tmp/aukcije-issue13-venv
/tmp/aukcije-issue13-venv/bin/pip install -r spike/issue-13/requirements.txt
/tmp/aukcije-issue13-venv/bin/python spike/issue-13/verify.py
```

The verifier makes no network calls. The raw capabilities XML and map imagery
are intentionally not versioned.
