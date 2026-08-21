## Outcome

Use option B to retrieve parcel geometry for the owner's actual scope: occasional, manually initiated, private, non-commercial lookup. Keep the result local; do not turn this into an automated or commercial data product.

## Selected path

- **B — public OGC WFS to a private local GeoJSON artifact.**
- Public unauthenticated WFS 2.0.0 feature type: `dkp:dkp_parcels_weekly_only_utm`.
- Exact identity: `cadmun_name_lat` + `parcel_num`; Serbian Latin diacritics are significant.
- Feature type default CRS: `EPSG:25834`; lookup output explicitly requests and validates `EPSG:4326`.
- Capability: `PARCEL_GEOMETRY_PRIVATE_ON_DEMAND`.
- Usage scope: `OCCASIONAL_PRIVATE_NON_COMMERCIAL`.

This scope does not authorize scheduled/background access, bulk collection, browser-session reuse, credentials, personal records, a shared/product cache, commercial use, or redistribution.

## Deliverables

- Committed decision record with dated WFS capabilities/schema fingerprints and the revised legal/operational boundary.
- One-shot `fetch_parcel.py` command: one exact request, `count=2`, no automatic retry, 5 MB response cap, strict identity/CRS/geometry/property validation, collision-resistant output identity, and stable failure exits including mid-read transport failures.
- Private precise GeoJSON under a gitignored output directory.
- Redacted success/not-found/ambiguous/error evidence with no coordinates, credentials, personal data, or raw responses committed.
- Three KO + parcel cases and CRS transform proof.
- Exact revised acceptance contract applied to #21.

## Acceptance criteria

- DIMITROVGRAD/1572, ČAJETINA/4577/337, and VOŽDOVAC/7300/1 each produce exactly one validated private GeoJSON feature.
- Success, not found, ambiguity, invalid input, HTTP/open/read/TLS/schema/identity/CRS/geometry errors, excessive JSON nesting, response limits, structurally malformed or degenerate rings, and no-write-on-failure behavior are characterized and tested offline, including with Python optimization enabled.
- Only exact validated WFS polygons receive `PARCEL` precision; #23 remains the deterministic fallback.
- Application/runtime code makes no RGZ request. Retrieval remains an explicit owner command and private artifact.
- Raw XML/GeoJSON captures are deleted after redacted fingerprints are recorded; private outputs remain ignored by Git.

## Evidence

See `documentation/2026-08-21-decision-13-rgz-parcel-access.md` and `spike/issue-13/` in the repository. Leave this issue open after implementation for owner review of the narrowed-use decision and retrieved data.

## Dependencies

None. The resulting private artifact/import contract narrows #21; all fallback behavior continues through #23.
