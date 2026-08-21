## Outcome

Implement the explicit unavailable behavior selected by #13. RGZ parcel geometry is not a production dependency; the resolver proceeds deterministically to #23 without overstating precision.

## Fixed decision from #13

- Decision: **C — view-only / unsupported / unconfirmed**.
- Capability: `PARCEL_GEOMETRY_UNAVAILABLE`.
- Reason: `UNCONFIRMED_REUSE_AUTHORITY`.
- Decision version/date: `2026-08-21`.
- The public RGZ/GeoSrbija map and observed WMS are research evidence only. No production request, proxy, cache, credential, browser session, or response is permitted.

This is a small wiring task, not an RGZ integration. Reopen the source decision only with written RGZ authority that identifies the dataset/license or contract and defines automated access, identity, geometry, CRS, versioning, quota, availability, caching, retention, and redistribution.

## Requirements

- Persist/expose the versioned capability and reason above in the spatial-resolution provenance owned by #20.
- Hand unresolved parcel references to #23 without an external call. The handoff is deterministic and idempotent.
- Reserve `PARCEL` for verified cadastral geometry only. An Address Registry house-number point on the same KO + parcel is `ADDRESS`; KO/settlement/municipality centroids remain explicitly coarse.
- Make the unavailable capability and maximum currently achievable precision available to #26/#27 so the UI cannot imply that a coarse point is a cadastral parcel.
- Preserve any previously valid resolution when the unavailable decision is re-evaluated or a reprocessing attempt fails. Do not delete or downgrade last-valid evidence.
- Record an auditable attempt/result without endpoint URLs, credentials, personal data, or undocumented RGZ response content.
- Do not implement dormant RGZ API, WMS, WFS, browser-scraping, or download code under this issue.

## Acceptance criteria

- Contract fixtures cover: unavailable capability, reason/version provenance, no-network #23 handoff, idempotent repetition, Address Registry `ADDRESS` precision, coarse fallback precision, and preservation of a last-valid resolution.
- A negative-control test fails if the selected path attempts any non-local RGZ/GeoSrbija request.
- Repeated resolution produces one stable unavailable decision/attempt and the same fallback result.
- UI/API consumers can distinguish `PARCEL_GEOMETRY_UNAVAILABLE` from `NONE` and can distinguish every `LocationPrecision` value.
- No registry house-number point or centroid is labelled `PARCEL`.
- No production code, configuration, fixture, or test depends on an undocumented browser-session token or the observed public WMS.

## Dependencies

Depends on #13 (decision complete), #16, #20, and #33. Scope and priority are informed by #32; implementation continues through #23.
