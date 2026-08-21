# Spike #32 — KO + parcel to map-location hit rate

**Issue:** [#32](https://github.com/brzivoz/aukcije_core/issues/32) ·
**Measurement date:** 2026-08-21 ·
**Status:** **COMPLETE — feasible with a measured precision ceiling** ·
**Code:** `spike/issue-32/`

## Verdict

**GO for a map MVP that exposes precision honestly. NO-GO for a product claim
that auctions are generally mapped to cadastral parcels without #21.**

The complete current root-`7` population can be placed at some resolution:
589/589 auctions (100.0%) reached an official Address Registry point or a
centroid derived from those points. Only 96/589 (16.3%) reached address-level
precision: 95 through a KO + parcel join and one through an exact address.
The other 493 placements are settlement or KO centroids.

The parcel-identifier path is technically viable but narrow. A KO + parcel
reference was extractable from 440/589 auctions (74.7%), yet only 95/440
(21.6%) of those identities had a house-number point in the Address Registry.
This is expected source coverage, not a join implementation failure: bare
land commonly has no house number. The dominant agricultural and land
categories are precisely where the join performs worst.

The project is therefore feasible if the UI and API preserve the measured
precision (`ADDRESS`, `KO`, `SETTLEMENT`) and never render coarse centroids as
parcel locations. Verified cadastral geometry remains required to improve the
land-heavy corpus beyond that ceiling.

## 1. Reproducible inputs

### eAukcija corpus

The live API had drifted from the issue's 622-record scale note by the time of
measurement. The script validated the API envelope and `TotalCount`, removed
duplicates by auction id, fetched every detail, and failed closed on any
unresolved detail request.

| Property | Measured value |
|---|---:|
| Root category | `7` |
| Live `TotalCount` | 589 |
| Unique listing records | 589 |
| Duplicate ids removed | 0 |
| Detail records fetched | 589/589 |
| Fetch completed | `2026-08-21T17:20:52Z` |

Source: `out/corpus_provenance.json`, produced by `01_fetch_auctions.py`.

### Official Address Registry artifact

The loader used the official `kucni_broj_ar` export linked by the Serbian open
data Address Registry dataset. The endpoint returned a ZIP containing one
GPKG. Both layers of provenance are retained so a future weekly snapshot
cannot be mistaken for this one.

| Property | Measured value |
|---|---|
| Dataset | `https://data.gov.rs/sr/datasets/adresni-registar/` |
| Resource id | `be7c80e3-206b-46af-b31d-4b9f6ae596f9` |
| Named snapshot date | `2026-08-21` |
| Download | `kucni_br_gpkg.zip`, 265,811,831 bytes (253.5 MiB) |
| Download SHA-256 | `75fe9058ffd5dab3f5b2b8723f0e352f3516519476e7789a38a3346cecef75fc` |
| Archive member | `kucni_broj.gpkg`, 995,225,600 bytes (949.1 MiB) |
| GPKG SHA-256 | `b78cdb490df67acd1507a6484b39cca477c04da40ee6b824f36742315d39c84e` |
| Layer / geometry | `kucni_broj` / Point |
| CRS | EPSG:25834, transformed to EPSG:4326 |
| Schema SHA-256 | `5ec25f25a7958ae1707e6f1875d9faa866dbcedc1f44d31315dbddf15e6859fb` |

The resolved source columns are recorded in `out/gpkg_report.json`. In
particular, `kat_opstina_ime`, `ko_maticni_broj`, and `broj_parcele` form the
parcel identity. `broj_dela_parcele` is a building/object part ordinal. The
loader stores it separately and never fabricates a cadastral `/subparcel` from
it.

## 2. Extraction

The extractor intentionally remains crude; #18 and #19 own production
parsing. It reads `Description` and `ShortDescription`, returns every distinct
parcel reference, and treats `Place.Cadastral` as the default structured KO.

| Metric | Count | % of 589 |
|---|---:|---:|
| KO in `Place.Cadastral` | 589 | 100.0% |
| KO also extracted from text | 395 | 67.1% |
| Structured/text KO agreement | 344 | 58.4% |
| Auction with at least one parcel | 440 | 74.7% |
| Total distinct parcel references | 451 | 76.6% |
| Auction with multiple parcels | 10 | 1.7% |
| Parcel found in `Description` | 421 | 71.5% |
| Parcel found only in `ShortDescription` | 19 | 3.2% |
| Street | 18 | 3.1% |
| Street + house number | 17 | 2.9% |
| Neither parcel nor street | 149 | 25.3% |

Of the 395 records where text yielded a KO, 344 (87.1%) agreed with the
structured field and 51 did not. Some disagreements are regex over-capture,
but some are genuine data conflicts. Examples include auction `179324`
(`Place.Cadastral = СЈЕНИЦА`, text `КО Урсуле`) and `180149`
(`Place.Cadastral = ВАЛАКОЊЕ`, text `КО МАЛИ ИЗВОР`). Production parsing must
not silently choose either side of such a conflict.

The live data also disproves the earlier fixture conclusion that KO agreement
was perfect. The durable finding is narrower: the structured KO has 100%
coverage and is the correct default, while text KO is a conflict signal and a
candidate only after normalization, official-identity validation, and an
ambiguity check.

## 3. Official GPKG load and parcel identity

| Property | Measured value |
|---|---:|
| Features / rows loaded | 2,488,492 |
| Rows skipped for missing/non-point geometry | 0 |
| Active rows | 2,488,492 |
| Inactive / retired rows | 0 / 0 |
| Rows carrying a parcel id | 2,488,492 |
| Distinct official KO + parcel identities | 2,187,593 |
| Load + transform + all indexes | 116.1 seconds |
| Indexed SQLite | 1,271,947,264 bytes (1,213.0 MiB / 1.18 GiB) |
| Peak retained files (ZIP + GPKG + SQLite) | 2.36 GiB |

For #22, budget at least 4 GiB of working disk per refresh so download,
extraction, indexed replacement, and safe atomic rollover have headroom.
Runtime on this development machine is under two minutes after download.

### Zero / one / many semantics

There are two different populations and both matter:

| Population | Zero points | One point | Many points |
|---|---:|---:|---:|
| All identities that exist in the registry | not representable | 2,004,604 (91.64%) | 182,989 (8.36%) |
| 440 auction KO + parcel attempts | 345 (78.4%) | 74 (16.8%) | 21 (4.8%) |

The registry's largest identity has 506 house-number rows. Among the 95
successful auction joins, 74 returned one point and 21 returned 2–13 points.
No accepted candidate set spanned more than 343.3 m; the resolver rejects sets
spanning more than 2,000 m or more than one official KO id.

A successful one-to-many join is not collapsed into a fabricated mean. The
resolver selects the observed registry point nearest the candidate cluster
centre, preserves candidate count and spread, and reports `ADDRESS`
precision. Only verified cadastral geometry may report `PARCEL` precision.

## 4. Resolution hit rates

The pipeline attempted parcel identity, exact address, street, KO centroid,
settlement centroid, and municipality centroid in that order.

| Winning tier | Count | % of 589 | Reported precision |
|---|---:|---:|---|
| Address Registry KO + parcel join | 95 | 16.1% | `ADDRESS` |
| Exact street + house number | 1 | 0.2% | `ADDRESS` |
| Street | 0 | 0.0% | `STREET` |
| KO centroid | 491 | 83.4% | `KO` |
| Settlement centroid | 2 | 0.3% | `SETTLEMENT` |
| Municipality centroid | 0 | 0.0% | `MUNICIPALITY` |
| None | 0 | 0.0% | `NONE` |

Six placements required a safe `UNIQUE_KO_ID_FALLBACK` because eAukcija and
the registry express administrative ownership differently. The fallback is
accepted only when the normalized KO name resolves to one official KO id.
This handles names such as Belgrade/Niš city municipalities without pretending
that the municipality strings are globally canonical.

The category split explains the product ceiling:

| Category | Auctions | With parcel text | Registry parcel join |
|---|---:|---:|---:|
| Пољопривредно земљиште | 178 | 170 | 4 (2.2%) |
| Парцела | 127 | 40 | 5 (3.9%) |
| Непокретности | 87 | 77 | 19 (21.8%) |
| Шумско земљиште | 60 | 60 | 0 (0.0%) |
| Остало земљиште | 32 | 5 | 0 (0.0%) |
| Кућа | 30 | 23 | 22 (73.3%) |
| Грађевинско земљиште | 26 | 21 | 9 (34.6%) |
| Објекат | 17 | 16 | 14 (82.4%) |
| Стан | 12 | 11 | 11 (91.7%) |
| Стамбени објекат | 6 | 5 | 5 (83.3%) |
| Other nine categories | 14 | 12 | 6 (42.9%) |

Address Registry parcel joins work well for built property and poorly for
bare land. A single global parser or geolocation threshold would obscure this
structural difference.

## 5. Individual public-map checks

`06_spotcheck.py --n 20 --seed 32` deterministically oversamples the precise
tier. Its versioned verdict file is coordinate-bound: stage 6 fails if a
future artifact changes a checked coordinate, preventing stale verdict reuse.

Each selected coordinate is an observed official Address Registry point tied
to the joined KO + parcel identity. Its OpenStreetMap reverse-geocoded public
map context was manually compared with the auction's municipality, settlement,
or KO. **Result: 20 correct, 0 near, 0 wrong.** This is an
independent visible-location context check. It does not prove cadastral
boundary geometry; that remains #21's job.

| # | Auction | KO / parcel | Coordinate | Candidates / spread | Verdict | Public-map observation |
|---:|---|---|---|---:|---|---|
| 1 | [180466](https://eaukcija.sud.rs/#/aukcije/180466) | ДИМИТРОВГРАД / 1572 | `43.013322, 22.780484` | 1 / 0.0 m | correct | Dimitrovgrad, Hrista Boteva 23 |
| 2 | [179413](https://eaukcija.sud.rs/#/aukcije/179413) | СТАРИ ГРАД / 3502 | `46.100755, 19.650804` | 1 / 0.0 m | correct | Subotica, Novo Selo |
| 3 | [179421](https://eaukcija.sud.rs/#/aukcije/179421) | БОГОЈЕВО / 573 | `45.532121, 19.141174` | 1 / 0.0 m | correct | Bogojevo, municipality Odžaci |
| 4 | [179415](https://eaukcija.sud.rs/#/aukcije/179415) | ЧАЈЕТИНА / 4577/337 | `43.734697, 19.693799` | 1 / 0.0 m | correct | Zlatibor, municipality Čajetina |
| 5 | [179051](https://eaukcija.sud.rs/#/aukcije/179051) | ВАЉЕВО / 7840 | `44.268211, 19.876412` | 1 / 0.0 m | correct | Valjevo, Partizanska 10 |
| 6 | [181466](https://eaukcija.sud.rs/#/aukcije/181466) | ВОЖДОВАЦ / 7300/1 | `44.770078, 20.495631` | 6 / 343.3 m | correct | Belgrade–Voždovac, Braće Jerković |
| 7 | [180636](https://eaukcija.sud.rs/#/aukcije/180636) | ВРАЊЕ I / 10941/4 | `42.534055, 21.906373` | 1 / 0.0 m | correct | Vranje, Donje Vranje |
| 8 | [181103](https://eaukcija.sud.rs/#/aukcije/181103) | ВЕЛИКИ ГАЈ / 625 | `45.287522, 21.174921` | 2 / 21.3 m | correct | Veliki Gaj, municipality Plandište |
| 9 | [181101](https://eaukcija.sud.rs/#/aukcije/181101) | ОЏАЦИ / 155 | `45.513740, 19.261358` | 1 / 0.0 m | correct | Odžaci, Vase Stajića 16 |
| 10 | [181256](https://eaukcija.sud.rs/#/aukcije/181256) | СТРОЈКОВЦЕ / 4440/1 | `42.905370, 21.925273` | 1 / 0.0 m | correct | Strojkovce, municipality Leskovac |
| 11 | [181464](https://eaukcija.sud.rs/#/aukcije/181464) | НОВИ САД I / 5429 | `45.263897, 19.814008` | 1 / 0.0 m | correct | Novi Sad, Detelinara |
| 12 | [181281](https://eaukcija.sud.rs/#/aukcije/181281) | СЈЕНИЦА / 1553/7 | `43.284084, 19.996639` | 1 / 0.0 m | correct | Sjenica, Valterova 5 |
| 13 | [181034](https://eaukcija.sud.rs/#/aukcije/181034) | ПАНЧЕВО / 928/4 | `44.884178, 20.639317` | 1 / 0.0 m | correct | Pančevo, Gornji grad |
| 14 | [180848](https://eaukcija.sud.rs/#/aukcije/180848) | ХРТКОВЦИ / 397 | `44.877546, 19.775884` | 1 / 0.0 m | correct | Hrtkovci, municipality Ruma |
| 15 | [179036](https://eaukcija.sud.rs/#/aukcije/179036) | КРАГУЈЕВАЦ I / 1307 | `44.029365, 20.959133` | 1 / 0.0 m | correct | Kragujevac, Maršić |
| 16 | [180309](https://eaukcija.sud.rs/#/aukcije/180309) | БОГАТИЋ / 5438 | `44.842922, 19.469263` | 1 / 0.0 m | correct | Bogatić, Nebojše Jerkovića 103 |
| 17 | [181681](https://eaukcija.sud.rs/#/aukcije/181681) | МИРИЈЕВО / 2145/2 | `44.784833, 20.526257` | 1 / 0.0 m | correct | Belgrade–Zvezdara, Zeleno brdo |
| 18 | [179985](https://eaukcija.sud.rs/#/aukcije/179985) | ПАЛИЛУЛА / 338/1 | `44.816630, 20.504999` | 13 / 157.8 m | correct | Belgrade–Palilula |
| 19 | [179002](https://eaukcija.sud.rs/#/aukcije/179002) | ГОРЊА ВРАЊСКА / 2770 | `44.675548, 19.695412` | 1 / 0.0 m | correct | Gornja Vranjska, municipality Šabac |
| 20 | [181327](https://eaukcija.sud.rs/#/aukcije/181327) | ЗЕМУН / 12015 | `44.842848, 20.380975` | 5 / 31.7 m | correct | Belgrade–Zemun, Novi grad |

## 6. Required issue recommendations

### #19 — revise quality thresholds and conflict handling

1. Read `Description` and `ShortDescription`; 19 auctions would otherwise
   lose their only parcel extraction.
2. Emit all parcel references, not only the first; 10 auctions carry multiple
   parcels and the live corpus contains 451 references across 440 auctions.
3. Use `Place.Cadastral` as the default KO because it has 100% coverage, but
   do not call it authoritative when text disagrees. Record a conflict and
   require normalization plus a unique official KO identity before fallback.
4. Set category-specific recall thresholds. Parcel text exists in 170/178
   agricultural auctions but only 40/127 generic `Парцела` auctions. More
   regex cannot recover data that is absent.
5. Maintain the precision traps already covered here: never confuse folio or
   object-part numbers with parcel/subparcel numbers, and normalize Serbian
   Cyrillic/Latin names and administrative suffixes.

### #21 — keep high priority; it closes the dominant precision gap

Do not deprioritize #21. The Address Registry provides a valuable no-RGZ
address path, but it reaches only 4/178 agricultural, 5/127 generic parcel,
and 0/60 forest-land auctions. Those categories dominate the corpus. #21 is
not required to launch an honest coarse map, but it is required before parcel
precision can be promised for the core land use case.

The integration must still obey #21's legal/time-box decision. If verified
parcel geometry cannot be obtained lawfully and reproducibly, the product
ceiling remains the measured `ADDRESS`/`KO` tiers; it must not be hidden with
fake parcel pins.

### #23 — keep the order, strengthen identity and ambiguity rules

Keep the resolution order, with these concrete rules:

1. Verified cadastral polygon from #21 → `PARCEL`.
2. Address Registry KO + parcel → an observed point and `ADDRESS`, never
   `PARCEL`.
3. Municipality + settlement + street + normalized house number → `ADDRESS`.
4. Street → `STREET`; KO centroid → `KO`; settlement/municipality centroids
   remain explicit coarse fallbacks.
5. Prefer municipality + official KO id + parcel identity. Permit a name-only
   administrative fallback only when it resolves to one KO id.
6. Retain every candidate count and spread. Reject multiple KO ids or a
   dispersed candidate set; never take the first row and never average precise
   candidates into a coordinate that does not exist in the source.
7. Persist and expose the winning tier/precision so clients cannot display a
   KO centroid with parcel-level styling.

## 7. Reproduction

Python 3.9+ is sufficient; the pinned dependencies are Fiona 1.10.1 and
pyproj 3.6.1.

```bash
cd spike/issue-32
python3 -m venv .venv-spike32
.venv-spike32/bin/pip install -r requirements.txt

PYTHON_BIN=.venv-spike32/bin/python \
  GPKG_SOURCE_DATE=2026-08-21 \
  ./run_all.sh

# Re-capture public-map context only when re-checking the versioned verdicts.
.venv-spike32/bin/python 06_spotcheck.py \
  --n 20 --seed 32 --reverse-osm
```

For exact reproduction from the already named ZIP, set
`GPKG=out/adresni_registar_download`. Generated corpus, registry, reports,
GeoJSON, public-map context, and sheet stay under ignored `out/`; the manual
coordinate-bound verdicts are versioned in `spotcheck-verdicts.json`.

Number provenance:

| Record section | Generated source |
|---|---|
| Corpus | `out/corpus_provenance.json` |
| Extraction | `out/extraction_stats.json`, `out/refs.json` |
| Artifact/load/registry identity | `out/gpkg_report.json` |
| Resolution and auction identity | `out/resolution_stats.json`, `out/resolved.json` |
| Map | `out/placements.geojson` |
| Individual checks | `spotcheck-verdicts.json`, rendered to `out/spotcheck_sheet.md` |

## 8. Acceptance criteria

| Criterion | Result |
|---|---|
| At least 100 representative auctions | **Met:** complete live population, 589/589 details |
| Named official GPKG artifact | **Met:** 2026-08-21 plus ZIP/GPKG hashes |
| Reproducible tier hit rates | **Met:** all tiers and zero/one/many semantics generated |
| Throwaway map output | **Met:** 589-feature GeoJSON |
| At least 20 individual public-map checks | **Met:** 20 listed, coordinate-bound verdicts; 20 correct |
| Recommendations for #19, #21, #23 | **Met:** concrete changes above |
| Five-working-day time box | **Met:** completed in the spike; no production scope added |

The uncertainty named by #32 is retired. The remaining uncertainty is not
whether a map can be built; it is whether #21 can legally and technically
raise land auctions from honest KO centroids to verified parcel geometry.
