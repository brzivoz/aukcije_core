# Issue #13 - private RGZ parcel lookup

This directory contains the evidence and one-shot lookup command for
[issue #13](https://github.com/brzivoz/aukcije_core/issues/13). The selected
path is option B: an unauthenticated public WFS used only for occasional,
manually initiated, non-commercial private lookups.

The decision and its boundaries are in
[`documentation/2026-08-21-decision-13-rgz-parcel-access.md`](../../documentation/2026-08-21-decision-13-rgz-parcel-access.md).

## Fetch one parcel

```bash
python3 spike/issue-13/fetch_parcel.py --ko 'ČAJETINA' --parcel '4577/337'
```

The command performs one exact KO + parcel request, asks for at most two
features, validates the response, and writes a property-whitelisted GeoJSON to
`spike/issue-13/out/`. That directory is ignored by Git. Serbian Latin
diacritics are significant: `ČAJETINA` and `CAJETINA` are not the same WFS
identity.

Stable exits are:

| Exit | Meaning |
|---:|---|
| 0 | One exact, valid parcel saved |
| 2 | Invalid local input; no request made |
| 3 | Not found |
| 4 | Ambiguous (at least two exact matches) |
| 5 | HTTP, timeout, media-type, JSON, identity, CRS, area, or geometry failure |

This is not a batch tool. Do not put it in a scheduler, loop it over a corpus,
or redistribute its output under this decision.

## Evidence

| Path | Purpose |
|---|---|
| `fixtures/guest-map-observation.json` | Earlier whitelisted guest UI/WMS observation |
| `fixtures/capabilities-parcel-layer.json` | Earlier sanitized WMS capabilities subset |
| `fixtures/wfs-observations.json` | Sanitized WFS capability/schema and behavior fingerprints |
| `fixtures/ko-parcel-samples.json` | Three #32 identity and CRS samples |
| `fixtures/private-wfs-contract.json` | Machine-readable private-use contract |
| `issue-body.md` | Reproducible live #13 contract |
| `downstream-issue-21.md` | Exact replacement body for issue #21 |
| `verify.py` | Offline fixture, redaction, CRS, and client behavior checks |

## Verify offline

```bash
python3 -m venv /tmp/aukcije-issue13-venv
/tmp/aukcije-issue13-venv/bin/pip install -r spike/issue-13/requirements.txt
/tmp/aukcije-issue13-venv/bin/python spike/issue-13/verify.py
```

The verifier makes no RGZ/GeoSrbija requests.
