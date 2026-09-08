# Issue #42 — building contract review remains open

## Amended disposition — 2026-09-08

**Keep #42 open pending verification of the building contract.** The previous
not-feasible recommendation is withdrawn: inability to read the endpoint on
2026-09-03 was not evidence that an appropriate layer or identity join was absent.

The current WFS inventory advertises `dkp:objekat`, default `EPSG:32634`.
Its unauthenticated DescribeFeatureType recheck on 2026-09-08 returned HTTP 200,
3,024 bytes, SHA-256
`06d95eb06d4d4a5408e8156df22a43082101affaf3b2b5ca4a6d6e6df87f8658`.

Schema candidates:

- `objectid`: `xsd:int`;
- `maticnibrojko`: `xsd:int`, with `brparcele`: `xsd:string`, is a KO/parcel
  join candidate, not yet a verified working join;
- `brdelaparc`: `xsd:int` and `deoparcele_id`: `xsd:string` may support
  parcel-part/object disambiguation;
- `wkb_geometry`: `gml:GeometryPropertyType` does not by itself establish the
  actual footprint geometry type.

No building records were fetched. Before implementing #42, verify a bounded
exact-identity join, actual footprint geometry and CRS behavior, one-to-many
handling, a non-personal whitelist, dataset identity, and the building-specific
access/retention scope. Retain sanitized evidence and record the resulting
access decision. Metadata availability is neither a licence nor RGZ consent.

The current #41 decision authorizes implementation of the private parcel path
only; it does not enable building fetching. Do not infer a footprint from the
layer name, scrape the portal, reuse browser sessions, or retain personal data.
Object-type auctions continue to use an exact #21 parcel polygon when available,
then #23's honest fallback tiers. Never choose an arbitrary structure or invent
its footprint. Building review does not block initial parcel visualization.
