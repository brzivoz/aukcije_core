## Outcome

Implement the option-B decision from #13 as an opt-in, user-initiated import of one private RGZ WFS parcel result. Do not add automatic RGZ access to the running application. Not-found, ambiguity, and failure continue deterministically through #23 without overstating precision.

## Fixed decision from #13

- Decision: **B — public OGC WFS to a private local artifact**.
- Capability: `PARCEL_GEOMETRY_PRIVATE_ON_DEMAND`.
- Usage scope: `OCCASIONAL_PRIVATE_NON_COMMERCIAL`.
- Decision version/date: `2026-08-21`.
- WFS: 2.0.0 `GetFeature`, feature type `dkp:dkp_parcels_weekly_only_utm`, unauthenticated, JSON output.
- Lookup identity: exact `cadmun_name_lat` + `parcel_num`; Serbian Latin diacritics are significant.
- CRS: feature type advertises `EPSG:25834`; the one-shot command requests and requires `EPSG:4326`.

This decision does not authorize scheduled/background access, bulk retrieval, browser-session reuse, personal-data collection, a shared/product cache, commercial use, or redistribution. A scope change requires a new #13 review.

## Requirements

- Consume only a user-selected GeoJSON artifact produced by `spike/issue-13/fetch_parcel.py`; the application must not call RGZ/GeoSrbija.
- Validate the artifact contract before persistence: one feature, exact KO + parcel, numeric KO code, declared `EPSG:4326`, positive finite area, and a correctly nested `Polygon` or `MultiPolygon` whose rings are closed, non-degenerate, and within broad Serbia bounds.
- Record provenance: capability/version, retrieval time, exact KO + parcel, KO code, geometry type, source projection/scale when present, and raw-response SHA-256. Never record cookies, credentials, browser state, or personal data.
- Make import idempotent by stable KO code + parcel number + response hash. Re-importing the same artifact must not duplicate a resolution or audit event.
- Reserve `PARCEL` for a validated exact WFS geometry. Address Registry house-number points remain `ADDRESS`; KO/settlement/municipality centroids remain explicitly coarse.
- Preserve the last valid parcel result when a later manual import fails. Do not delete or downgrade last-valid evidence.
- For not found, ambiguity, invalid identity, HTTP/schema/CRS/geometry error, or no private artifact, write no parcel result and hand resolution to #23.
- Expose provenance and precision to #26/#27 so UI/API consumers can distinguish a verified parcel polygon from every fallback tier.

## Acceptance criteria

- Fixtures cover exact success for DIMITROVGRAD/1572 (`Polygon`), ČAJETINA/4577/337 (`MultiPolygon`), and VOŽDOVAC/7300/1 (`Polygon`).
- Fixtures cover not found, at least-two-feature ambiguity, invalid input, open/read/TLS and response/schema errors, excessive JSON nesting, exact-identity mismatch, wrong CRS, malformed/degenerate geometry, output-name collisions, and response-size limit.
- A negative-control test fails if application startup, scheduled work, request handling, or reprocessing attempts an RGZ/GeoSrbija network call.
- Importing the same valid artifact twice produces one current resolution and stable provenance; importing a different valid hash creates auditable supersession without erasing the prior evidence.
- Failed or absent imports continue through #23 and never label a registry point or centroid as `PARCEL`.
- Persistence/export tests prove no cookie, credential, session token, personal field, or unrecognized future WFS property crosses the whitelist.
- Private WFS GeoJSON remains outside version control and is never included in public exports/backups by default.

## Dependencies

Depends on #13 (option-B private-use contract), #16, #20, and #33. Scope and priority are informed by #32; fallback behavior continues through #23.
