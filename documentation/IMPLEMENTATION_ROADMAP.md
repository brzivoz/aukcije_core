# Aukcije Core — Audited Implementation Roadmap

First audited: 2026-08-21
Re-audited and rewired: 2026-08-21
GitHub repository: [brzivoz/aukcije_core](https://github.com/brzivoz/aukcije_core)

## Executive summary

The GitHub plan consists of 9 epics and 25 executable implementation/decision issues. Every issue has a priority, a size estimate, a milestone, explicit dependencies, testable acceptance criteria, and required completion evidence.

Both external feasibility gates now have committed outcomes:

- **#13 — REOPENED, option B verified for private use:** the public WFS returned three exact parcel geometries. Access is restricted to an occasional owner-initiated command and private local artifact; the running application still makes zero RGZ requests.
- **#32 — COMPLETE, feasible with a measured ceiling:** every measured auction reached some location tier, but only 16.3% reached address precision; no Address Registry point is promoted to parcel precision.

Three milestones define completion:

| Milestone | Goal | Exit condition |
|---|---|---|
| `M0 — Feasibility & Data Foundation` | Verified source contract, tests, PostGIS, resilient sync, immutable snapshots, measurable parser, both feasibility gates answered | Source can be replayed and reprocessed deterministically with complete-run evidence |
| `M1 — Geospatial Map MVP` | Lawful location resolution, spatial model, offline Serbia basemap, bounded GeoJSON, accessible map | Map works with network restricted to localhost and represents location precision honestly |
| `M2 — Operational Daily Use` | Deterministic reprocessing, status/metrics, unified filters/list-map workflow, hardened private release | Fresh-machine release/backup/restore checklist passes with retained evidence |

## Measured source facts

Every figure below was verified directly against the live API on 2026-08-21.

| Query | Result |
|---|---|
| Root `7` (immovable) | `TotalCount` 622, 622 unique IDs |
| Child `47` / `48` / `49` | 445 / 69 / 16, zero pairwise overlap |
| Union of children | 530 — leaving 92 root-only records |
| Root `8` and children `121`/`124`/`135` | 0 |
| Root `2` (movable, out of scope) | 2,237 |
| Records with a future `EndDate` | **602 of 622** |
| Historical records | **20, all from 2023** |
| Retrieval | all 622 returned in **one request** at `ItemCount=3000` |
| Live horizon | every open auction ends within roughly one month |

**This is a small, fast-moving dataset: about 600 live rows on a one-month horizon.** Every sizing decision in the plan should be checked against that number.

## First-audit corrections (retained)

1. **Category ingestion was over-specified and duplicative.** Root `7` returns 622 unique records; children `47`/`48`/`49` are disjoint subsets totaling 530, leaving 92 root-only. #12 discovers by roots `7` and `8`, deduplicates stable IDs, and uses children/detail categories only for classification.
2. **Presence is not activity.** The source includes historical auctions. #11 closes primarily from the source end instant and only uses absence after two complete successful cycles; partial runs cannot mutate lifecycle. (See correction 9 below for the revised magnitude.)
3. **Raw payload replay is real.** #10 stores sanitized, append-only listing+detail JSONB snapshots keyed by canonical content hash. Base64 thumbnails/images and transport secrets are excluded by a versioned minimization policy.
4. **RGZ parcel access is explicit and opt-in.** After the owner narrowed the scope to occasional private non-commercial use, #13 verified option B against a public WFS 2.0.0 contract. A manual one-parcel command produces a private local artifact; application/runtime code never calls RGZ, and missing or failed imports continue through #23.
5. **The address fallback is concrete.** The official weekly Address Registry GPKG includes house-number geometry and street, municipality, settlement, KO, and parcel identifiers. The inspected artifact used `EPSG:25834`; #22 validates and transforms it rather than relying on a live geocoder.
6. **Spatial persistence is production-shaped.** #15 standardizes `postgis/postgis:18-3.6`, Flyway, Hibernate Spatial, `ddl-auto=validate`, loopback database binding, and a clean re-sync from H2 while preserving any old H2 file for manual archive.
7. **The basemap is genuinely local.** #24/#25 use a checksum-verified Geofabrik Serbia extract, pinned Protomaps/Planetiler tooling, PMTiles v3, same-origin sprites/glyphs/style, HTTP byte ranges/ETags, atomic activation, and browser proof with non-local network blocked.
8. **Operational correctness is part of the MVP path.** CI/Testcontainers, incremental enrichment, immutable run evidence, status/readiness, redaction, localhost/private runtime controls, and backup/restore/release checks.

## Second-audit corrections (2026-08-21)

9. **The historical tail is 3%, not a subsystem.** Correction 2 is real but applies to 20 of 622 rows, all from 2023. The `EndDate`-primary closure rule and the two-complete-run absence rule are kept because they are correct and cheap; the elaborate reconciliation machinery built around them is not warranted. #11 now states the measured magnitude and tests against the real population size.

10. **The plan was sized for a system a hundred times larger than the one that exists.** Removed or downgraded:
    - #26, #28, and #7 previously required "2,000 representative rows, warm p95 under 500 ms, retained query-plan evidence." At ~600 rows Postgres will sequential-scan and win. The GiST index is kept — it costs nothing and survives growth — but the benchmark harnesses and evidence rituals are replaced by correctness-and-boundedness bars.
    - #29 previously specified a leased durable queue: `FOR UPDATE SKIP LOCKED`, lease owners and expiry, multi-worker concurrency, per-job backoff with jitter. Peak backlog is a few hundred jobs, each a pure function of a locally stored snapshot; a full cold reprocess costs minutes on one thread. It is now **deterministic idempotent reprocessing**, with the leased queue deferred behind explicit trigger conditions (sustained backlog above ~5,000, cold reprocess above ~30 minutes, or a slow external dependency in an enrichment stage).
    - #17's pagination machinery is demoted to a defensive concern. All 622 records return in a single request; the current `page-size=10` default costs 63 round trips for nothing.
    - #18's corpus target drops from 100 auctions / 150 references to 60 / 100 for the first pass — the original was 16% of the entire population, hand-annotated and double-reviewed by one person, sitting on the critical path before any parser existed. The held-out split and double review are retained, since those are what make the numbers mean anything.

11. **The core product assumption was untested and scheduled fifteenth.** 87.7% of descriptions contain a parcel number, but nothing confirmed that a KO name plus a parcel number joins to anything positional. As originally sequenced this was first answered at #23. **#32** is a new five-day throwaway spike in wave 0 that measures the end-to-end hit rate and spot-checks placements by hand. It informs #19's thresholds, #21's priority, #22's indexing, and #23's resolution order.

12. **The registry may already carry the parcel path.** #22 records that the GPKG holds **parcel identifiers** alongside house-number geometry. If that join works, most auctions get placed through EPIC-04 with no RGZ dependency at all, and EPIC-03 becomes marginal precision rather than a prerequisite. #32 measures this; #23 gains it as resolution tier 1, reported honestly as `ADDRESS` — a house-number point on a parcel is never labelled `PARCEL`.

13. **A false dependency serialized the longest-lead item.** #22 depended on #20 → #19 → #18 → #10 → #17. Importing a 1 GB GPKG into PostGIS needs nothing from the property-reference parser, the corpus, or the auction-side spatial model. **#22 now depends only on #15 and #16 and moves from wave 5 to wave 2**, owning its own Flyway migration range disjoint from #20's. #24 likewise runs early.

14. **#14 was split.** Building the KO dictionary needs only #22. Matching *extracted* names against it needs #19. Combined, the dictionary inherited the parser's whole dependency chain. **#14** is now dictionary-build only (after #22); **#33** is matching (after #14 and #19). Both moved from EPIC-03 to EPIC-04, since the dictionary is an Address Registry artifact the address path needs regardless of the RGZ outcome. #21 and #23 now consume #33.

15. **The browser-test foundation was required by three issues and owned by none.** #25, #27, and #28 each mandate browser tests including localhost-only network proof; #16 covers JUnit and Testcontainers only. Nothing selected a browser-automation tool, decided how JS and map assets are built and served, or said what happens to the existing Thymeleaf UI when MapLibre arrives. **#34** owns those four decisions and builds the single shared network-restriction fixture that #25 and #27 consume. Playwright is the recommended default for its request interception and trace artifacts.

16. **The legal scrutiny was one-sided.** An entire epic, a P0 gate, and explicit no-authenticated-scraping rules cover RGZ — and nothing covered eaukcija.sud.rs's own terms for automated access, the source the whole project depends on. `robots.txt` returns 404 and `WebApi.Proxy/*` is an undocumented SPA backend, not a published API. #17 now requires a short source acceptable-use note recording that state, the chosen rate limit and contact `User-Agent` as mitigation, and whether official contact was attempted. Deliberately far lighter than #13 — no gate, half a page — but the asymmetry is closed.

17. **Nothing was sized and no milestone had a date.** Every executable issue now carries `size:S` (1–2 focused days), `size:M` (about a week), or `size:L` (two weeks or more; consider splitting). The current distribution is 3 S, 15 M, 7 L. Milestones still have no due dates, because setting them requires a velocity assumption the repository cannot supply.

18. **The RGZ gate was reopened for the real private-use scope.** The earlier commercial/production assumption correctly selected C. The owner clarified that use is occasional, private, and non-commercial and requested A or B. A public unauthenticated WFS 2.0.0 advertises the weekly parcel feature type and returned exact polygons for all three KO+parcel samples. #13 therefore selects **B** only as a manual one-parcel-to-private-GeoJSON workflow. It does not authorize scheduled/bulk use, product caching, or redistribution. The decision record, sanitized WFS fingerprints, failure cases, one-shot command, CRS proofs, and exact #21 replacement contract are in `documentation/2026-08-21-decision-13-rgz-parcel-access.md` and `spike/issue-13/`.

## Honest total

Summing the size labels at 1.5 / 5 / 12 focused days gives roughly **165 focused days** to complete all three milestones as written. For one developer working evenings and weekends that is a **6–12 month** programme, with M1 — the first point at which the product does the thing it exists to do — arriving somewhere past the midpoint.

The P2/M2 work is the designated cut line. #28 and, if #32 or #13 go badly, #21 are the items to drop first if the schedule needs to give.

## Implementation order

Arrows are hard dependencies. The dashed #21 edge is optional precision: absent a manually imported private WFS artifact, #26 still proceeds through the address fallback in #23.

```mermaid
flowchart TB
    subgraph M0["M0 — Feasibility & Data Foundation"]
        I16["#16 CI + PostGIS tests"]
        I13["#13 RGZ private WFS: B"]
        I32["#32 End-to-end hit-rate spike"]
        I15["#15 PostgreSQL/PostGIS + Flyway"]
        I12["#12 Canonical source taxonomy"]
        I17["#17 Complete resilient sync runs"]
        I10["#10 Immutable source snapshots"]
        I11["#11 Delta + lifecycle"]
        I18["#18 Annotated extraction corpus"]
        I19["#19 Versioned extraction"]

        I16 --> I15
        I16 --> I12
        I12 --> I17
        I15 --> I17
        I17 --> I10
        I15 --> I10
        I10 --> I11
        I17 --> I11
        I10 --> I18
        I16 --> I18
        I18 --> I19
        I10 --> I19
        I15 --> I19
    end

    subgraph M1["M1 — Geospatial Map MVP"]
        I22["#22 Address Registry import"]
        I24["#24 Reproducible Serbia PMTiles"]
        I34["#34 Browser harness + frontend"]
        I14["#14 Canonical KO dictionary"]
        I33["#33 KO matching for extractions"]
        I20["#20 Spatial schema + bbox queries"]
        I25["#25 Range/ETag local serving"]
        I21["#21 Private parcel import"]
        I23["#23 Address/coarse resolver"]
        I26["#26 Bounded GeoJSON API"]
        I27["#27 MapLibre precision-aware map"]

        I15 --> I22
        I16 --> I22
        I16 --> I24
        I16 --> I34
        I22 --> I14
        I14 --> I33
        I19 --> I33
        I19 --> I20
        I15 --> I20
        I24 --> I25
        I34 --> I25
        I13 --> I21
        I33 --> I21
        I20 --> I21
        I33 --> I23
        I20 --> I23
        I22 --> I23
        I20 --> I26
        I23 --> I26
        I21 -. "optional parcel precision" .-> I26
        I25 --> I27
        I26 --> I27
        I34 --> I27
    end

    subgraph M2["M2 — Operational Daily Use"]
        I29["#29 Deterministic reprocessing"]
        I30["#30 Metrics + operator status"]
        I28["#28 Filters + list-map workflow"]
        I31["#31 Private runtime + release gate"]

        I11 --> I29
        I19 --> I29
        I21 --> I29
        I23 --> I29
        I22 --> I30
        I25 --> I30
        I29 --> I30
        I27 --> I28
        I30 --> I28
        I34 --> I28
        I28 --> I31
        I29 --> I31
        I30 --> I31
    end

    I32 -. "informs thresholds" .-> I19
    I32 -. "informs indexing" .-> I22
    I32 -. "informs resolution order" .-> I23
```

The critical path is the parser chain: `#16 → #15 → #17 → #10 → #18 → #19 → #33 → #23 → #26 → #27 → #28 → #31`. That chain is real and largely irreducible. What the rewiring removes is everything that was needlessly attached to it — the GPKG import, the basemap build, the KO dictionary, and the browser harness now all run alongside it rather than behind it.

## Issue hierarchy

| Epic | Priority / milestone | Children in execution order |
|---|---|---|
| [#1 Auction Data Foundation](https://github.com/brzivoz/aukcije_core/issues/1) | P0 / M0 | #16, #15 (cross-epic), #12, #17, #10, #11 |
| [#2 Property Reference Extraction](https://github.com/brzivoz/aukcije_core/issues/2) | P0 / M0 | #18, #19 |
| [#3 Lawful RGZ Parcel Resolution](https://github.com/brzivoz/aukcije_core/issues/3) | P1 / M1 | #13 (P0 gate), #21 |
| [#4 Official Address Resolution](https://github.com/brzivoz/aukcije_core/issues/4) | P1 / M1 | #32 (P0 spike), #22, #14, #33, #23 |
| [#5 PostgreSQL/PostGIS Spatial Store](https://github.com/brzivoz/aukcije_core/issues/5) | P1 / M1 | #15 (P0 prerequisite), #20 |
| [#6 Reproducible Local Serbia Basemap](https://github.com/brzivoz/aukcije_core/issues/6) | P1 / M1 | #24, #25 |
| [#7 Auction Map MVP](https://github.com/brzivoz/aukcije_core/issues/7) | P1 / M1 | #34, #26, #27 |
| [#8 Search, Filters & List–Map Workflow](https://github.com/brzivoz/aukcije_core/issues/8) | P2 / M2 | #28 |
| [#9 Durable Incremental Enrichment & Operations](https://github.com/brzivoz/aukcije_core/issues/9) | P1 / M2 | #29, #30, #31 |

GitHub sub-issues allow one parent each. Where the table says "cross-epic", the GitHub parent is the other epic and the listing here is informational.

Across the #8/#9 boundary the issue-level order is `#30 → #28 → #31` — a chain, not a cycle, despite the issues living in different epics. The earlier epic-level prose contradicted itself on this and has been corrected in both.

## Execution waves

Work inside a wave can run in parallel once its incoming dependencies are green.

| Wave | Issues | Evidence required before advancing |
|---|---|---|
| 0 | #16, #13, #32 | Terminal CI foundation; #13 option-B private WFS decision, one-shot lookup, and offline evidence verifier; committed #32 hit-rate measurement with hand spot-checks |
| 1 | #15, #12, #24, #34 | PostGIS migration/startup proof; taxonomy contract tests; validated PMTiles manifest; browser harness with a passing negative control |
| 2 | #17, #22, #25 | Complete/partial sync-run tests plus source acceptable-use note; validated GPKG import with atomic promotion and rollback; Range/ETag and localhost-only browser network proof |
| 3 | #10, #14 | Snapshot replay/hash evidence; reproducible KO dictionary with duplicate-name report |
| 4 | #11, #18 | Lifecycle matrix at real population size; reviewed corpus and baseline metrics |
| 5 | #19 | Held-out parser thresholds met, or a reviewed threshold revision citing #32 |
| 6 | #20, #33 | Migrated spatial model with indexed bbox plan; zero exact-match KO false positives, ambiguity left unresolved |
| 7 | #21, #23 | Selected parcel/fallback contract; held-out address-resolution results with zero false-positive exact matches |
| 8 | #26, #29 | GeoJSON contract evidence; idempotent reprocessing proven by kill-and-restart test, with cold-reprocess duration recorded |
| 9 | #27, #30 | Accessible offline map browser suite; persisted freshness/backlog/precision status |
| 10 | #28 | Full daily-use browser flow with URL round-trip and DST boundaries |
| 11 | #31 | Fresh-machine private release, backup/restore, and dependency/secret scan evidence |

## Definition of done for every issue

- The issue acceptance checklist is satisfied by current code and documentation, not only prose.
- Focused unit/integration/browser tests run from a clean checkout and have non-zero test counts.
- Schema/runtime work has PostgreSQL/PostGIS evidence; source integrations have redacted contract fixtures and no live-network tests in CI.
- Operator-visible behavior, safe defaults, failure/retry semantics, and rollback are documented.
- No raw base64 images, credentials, browser-session tokens, or unnecessary personal data appear in storage, logs, fixtures, or CI artifacts.
- The issue body is updated if implementation changes the contract, and closure includes exact commands, results, artifacts, and current terminal CI URL.

Spike issues (#13, #32) are exempt from the application test and CI requirements. Their deliverable is committed reproducible evidence. #13 additionally retains its bounded private lookup command; #32's measurement code remains disposable.

## Primary references

- [eAukcija public application](https://eaukcija.sud.rs/)
- [Official Serbian Address Registry dataset](https://data.gov.rs/sr/datasets/adresni-registar/)
- [RGZ GeoSrbija](https://www.rgz.gov.rs/geo-srbija) and [public cadastral map](https://portal.rgz.gov.rs/rgz-portal/map)
- [RGZ electronic-service terms](https://www.rgz.gov.rs/uslovi-kori%C5%A1%C4%87enja-elektronskih-servisa)
- [Issue #13 option-B private WFS decision record](2026-08-21-decision-13-rgz-parcel-access.md)
- [Geofabrik Serbia extract](https://download.geofabrik.de/europe/serbia.html)
- [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
- [Protomaps basemap generator](https://github.com/protomaps/basemaps), [PMTiles specification/implementations](https://github.com/protomaps/PMTiles), and [MapLibre PMTiles example](https://maplibre.org/maplibre-gl-js/docs/examples/pmtiles/)
- [Official PostGIS Docker image](https://github.com/postgis/docker-postgis)
- [Flyway PostgreSQL module](https://documentation.red-gate.com/fd/postgresql-database-277579325.html)
- [Hibernate Spatial](https://docs.hibernate.org/orm/current/userguide/html_single/Hibernate_User_Guide.html#spatial)

## Repository audit note

At audit time the application had 670 lines of Java, no test sources, used H2 with `ddl-auto=update`, configured only category `7` at `page-size=10`, performed insert-only sync, swallowed per-page errors, created a raw thread from the controller, and could mark missing detail as fetched. The issues above intentionally replace those behaviors before adding GIS/UI surface area.

The gap between that starting point and the plan's ambition is the reason for corrections 10 and 17. The analysis underneath this plan is sound and the parts that guard against being wrong — immutable snapshots, versioning, the precision ladder, the forced decision gates — are worth keeping in full. What needed recalibrating was the operational tier and the ordering.
