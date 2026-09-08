# Review of local #41 work against GitHub — 2026-09-08

## Follow-up — corrections implemented

The outdated building disposition and runtime findings 3–5 below have been
addressed. #42 now remains open pending its building contract review; ordinary
retry discovery and both cache behaviors have regression coverage. The original
three failing review probes now pass. See the
[correction verification](2026-09-08-issue-41-fixes-verification.md).
GitHub scope reconciliation and the remaining end-to-end/operator acceptance
items are not claimed complete. The findings and line references below are the
**historical pre-fix review**, retained rather than silently rewritten.

## Verdict (pre-fix)

**Do not close #41 as currently written/evidenced.** Its dated decision,
source citations, superseded-#13 pointer, and configurable access contract are
substantially present. However, the owner-authorized implementation scope needs
to be reconciled with the live issue's lawful-access gate, and the building-layer
closure rationale is incomplete and now has fresh contrary technical evidence.

The working tree also includes substantial **#21 runtime implementation**, not
just the #41 decision spike. That runtime is not ready to declare #21 complete:
three additional review regressions reproduce contract violations despite the
existing tests passing. These runtime corrections can be assigned to #21;
#41 need not become the entire implementation issue.

Reviewed GitHub issues: [#41](https://github.com/brzivoz/aukcije_core/issues/41),
[#21](https://github.com/brzivoz/aukcije_core/issues/21),
[#42](https://github.com/brzivoz/aukcije_core/issues/42),
[#23](https://github.com/brzivoz/aukcije_core/issues/23),
[#26](https://github.com/brzivoz/aukcije_core/issues/26),
[#27](https://github.com/brzivoz/aukcije_core/issues/27), and
[#30](https://github.com/brzivoz/aukcije_core/issues/30).
#41/#21/#42/#23 are open; #26/#27/#30 are closed. No GitHub issue was changed.
The implementation remains local/staged, on top of `fdb7018`.

## Findings

### 1. #41 closure blocker: scope differs from the live issue

GitHub #41 asks for a lawful automatic-access decision, with a manual outcome if
automation is unacceptable. The local decision instead selects
`OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION` and explicitly
says publisher machine-access/cache authority is unconfirmed
(`documentation/2026-09-03-decision-41-rgz-automatic-geometry-access.md:7-18`).

This honestly records the owner's direction to implement now and defer billing;
it is not evidence of RGZ permission. Record that scope amendment on GitHub and
in the closure rationale, or satisfy the original access gate. Do not label
successful unauthenticated HTTP access or empty capabilities fields a licence.
This review does not require implementing billing before private visualization;
it requires an accurate statement of what decision is being closed.

### 2. #41 closure blocker: the building answer must be revisited

The decision at lines 158–161 and `spike/issue-41/downstream-issue-42.md` propose
closing #42 because no object schema/join contract was retained and the endpoint
could not be revalidated. A timeout did not establish absence of a usable layer.

A bounded, unauthenticated metadata-only request on **2026-09-08 at 06:30 UTC**:

`https://ogc-tmp.geosrbija.rs/regdkp/ows?service=WFS&version=2.0.0&request=DescribeFeatureType&typeNames=dkp%3Aobjekat`

returned **HTTP 200**, `application/gml+xml; version=3.2`, **3,024 bytes** in
**0.129 seconds**. Schema SHA-256:
`06d95eb06d4d4a5408e8156df22a43082101affaf3b2b5ca4a6d6e6df87f8658`.

It declares these relevant candidates:

| Field | Schema type | Significance to investigate |
|---|---|---|
| `objectid` | `xsd:int` | Object identity candidate |
| `maticnibrojko` | `xsd:int` | KO identity candidate |
| `brparcele` | `xsd:string` | Parcel join candidate |
| `brdelaparc` | `xsd:int` | Parcel-part/object disambiguation candidate |
| `deoparcele_id` | `xsd:string` | Related parcel-part identity candidate |
| `wkb_geometry` | `gml:GeometryPropertyType` | Geometry field, not proof of footprint geometry type |

Fresh capabilities from the earlier check advertise default `EPSG:32634` for
`dkp:objekat`. No building records were fetched. A working join, actual geometry
type, whitelist, and access decision still need verification. **Do not close
#42 as technically not feasible on the old timeout rationale.**

Preserve historical evidence, but add current evidence and revise the decision
and verifier. `spike/issue-41/verify.py:79-83,135-139` currently hardcodes failed
reads, no schema hash, and `CLOSE_NOT_FEASIBLE`; a passing verifier therefore
cannot establish that those conclusions remain current. Also incorporate the
[fresh parcel recheck](2026-09-08-rgz-availability-recheck.md), including its
changed parcel-schema hash.

### 3. P1, #21 runtime: uncached failures are not automatically retried

`ParcelPathEnrichmentStage.java:34-38` reduces all unresolved RGZ outcomes to
`CONTINUE`. `EnrichmentPipeline.java:93-100` derives the final work status only
from the final fallback/selection stage. Candidate discovery in
`EnrichmentRunRepository.java:259-268` subsequently skips unchanged completed
work unless it is explicitly replayed or marked retryable/pending.

An isolated review regression drove the real parcel stage, final selection,
processor, and run repository with already extracted/matched input. A transport
timeout created no cache record, but the work became `TERMINAL_NOT_FOUND` and
the next ordinary discovery returned **zero candidates instead of one**.
A resolved coarse fallback similarly produces completed `SUCCEEDED` work. The
same outcome collapse affects request-ceiling/kill-switch declines: missing
parcels can remain on a fallback indefinitely while inputs/versions stay fixed.

Keep fallback serving separate from a durable pending/retry state for the finer
tier. Test actual run discovery, not just calling `stage.process` again with a
new run ID. The current per-run ceiling must allow the remaining identities to
be attempted by later ordinary runs.

### 4. P2, #21 runtime: disabling networking also bypasses cache reuse

`RgzParcelResolutionService.java:88-92` returns before candidate/cache lookup
when `rgz.enabled=false`, contrary to the decision's line 142. A review
regression cached an identity for one reference, disabled RGZ with the same pins
retained, and attempted a second reference to that identity: **`CONTINUE`, not
`RESOLVED`**. Previously selected geometry remains readable, but another
reference cannot reuse it. Gate outbound misses rather than the cache path.

### 5. P2, #21 runtime: cache key exceeds the documented dataset identity

`RgzParcelResolutionService.java:234-251` additionally keys cache reads by
capabilities SHA-256 (and resolver version), whereas the contract describes
feature type + dataset version + KO + parcel. Changing only the capabilities
hash in a later run caused **two client calls instead of one**, with the parcel
and dataset version unchanged. Align lookup uniqueness with the documented
contract; service-metadata changes must not silently force duplicate fetches.

### 6. #21 acceptance gaps remain beyond the passing suites

- `RgzParcelResolutionIntegrationTest.java:77-78` mocks the entire HTTP client;
  its persistence test calls the parcel stage directly and checks
  `SpatialViewportRepository`, not the HTTP-to-browser chain.
- The existing map browser suite seeds geometry directly
  (`AuctionMapBrowserTest.java:94`). It proves polygon presentation, not an
  activated fixture WFS → normal refresh → selected parcel → viewport → rendered
  polygon workflow. Add that end-to-end acceptance test to #21.
- The live #21 issue requires kill-switch visibility on the operator status
  surface. The local contract defers monitoring to #30, which is already closed;
  the status implementation has no RGZ-specific switch state. Either implement
  that small requirement or explicitly amend/defer it to a real follow-up.
- Activation still needs an established dataset/cache-version policy and current
  pins. WFS update sequence `6441` is not independently a parcel-data edition.

## Verification performed

- `python3 spike/issue-41/verify.py` and optimized `python3 -O ...`: passed.
- Targeted Java suites (`rgz`, `enrichment`, `spatial`, `map`, database lifecycle):
  **122 passed; zero failures/skips**.
- `./gradlew browserTest --no-daemon`: **24 passed; zero failures/skips**, including
  the localhost-only network guard.
- Three additional off-tree PostGIS review probes: **all three reproduced the
  expected contract failures** in findings 3–5. Final failures were assertions,
  not fixture setup errors. Temporary sources, Gradle init script, and results:
  `/tmp/aukcije-issue41-review-p8Mcl3UT/` (`rgzReviewTest`).
- Normal `testClasses` compilation restored after the probes; no diagnostic test
  sources or classes remain in the repository's normal source/build class set.
- No application settings, application database, existing staged files, or
  GitHub states were changed by the review. Raw schema bytes remain temporary.

## Next issue for polygons on the map

**#21 — Resolve parcel geometry automatically and cache it durably.** Finish
and verify the existing local runtime rather than starting a new map renderer.

The required rendering path already exists: #26 exports selected geometries;
`auction-map.mjs:627-680` accepts `Polygon`/`MultiPolygon` and draws area fills and
outlines. Close the reconciled #41 decision, correct #21's runtime gaps, establish
current activation pins/versioning, and prove one normal refresh against a local
WFS fixture produces a rendered `PARCEL` polygon. Then perform a bounded live
operator smoke check without making browser tests depend on RGZ availability.

#23 remains the subsequent precise-address/tier-selection work; it is not needed
to build a new polygon renderer. #42 is separate building-footprint work and
should not block initial parcel outlines. Commit/publish the reviewed evidence
and implementation before referring to them in a GitHub closure comment.
