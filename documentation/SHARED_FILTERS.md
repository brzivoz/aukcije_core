# Shared auction filters (#44)

## Using the page

There is one Serbian-labelled filter form above the table and map. **Примени
филтере** applies its criteria to both views and returns the table to page 1.
The active-criteria summary describes the *applied* state; typing alone does not
change results. Source/background refresh uses that applied state, not drafts.

- **Нису завршене** (default): known end time strictly after the evaluation
  instant. This includes future auctions and does **not** mean bidding is open.
- **Завршене**: known end time at or before the evaluation instant.
- **Све**: no implicit current-time restriction, including unknown end times.
- Dates refer to **end time**, in **Europe/Belgrade**, and intersect the scope.
  “До” includes the entire local day, including 23/25-hour DST days. Same-day
  ranges are valid; reversed ranges are errors. Unknown end times fail explicit
  date criteria. Contradictory scope/date combinations are allowed and explained
  as an empty intersection, never silently changed.
- **Општине** is a searchable checkbox dropdown: choose several to match **any**
  of them (OR), intersected with all other criteria. No selection means no
  municipality restriction. Search accepts Cyrillic or Serbian Latin and only
  narrows the options, not the results. Use Tab/Space to choose, Escape or an
  outside click to close, and **Примени филтере** to apply. Clearing the choice
  does not clear dates or other filters.
  All 168 names in the pinned RGZ municipality extract are available even
  without auctions, plus safe retained portal labels. Case variants collapse;
  name matching ignores case but does not guess aliases or change source text.
  See the [catalogue provenance/coverage](../src/main/resources/catalogue/README.md).
- **Изворна категорија**, including `Викендица`, is the retained raw category,
  not #12's normalized property kind or sale scope, and not `ImmovableProperties`.
  Source workflow status (including retained `Closed` and stale `InPrediction`)
  is separate from the time scope. Filtering never changes stored status.
- Starting-price bounds are inclusive **RSD**, with at most two decimal places.
  Blank first-sale means any; yes/no mean true/false, not presence/absence.
- Text search covers auction number, short description and description only.
  Cyrillic and Serbian Latin share a case/diacritic-insensitive search spelling
  (`Љ`/`lj`, `Њ`/`nj`, `Ђ`/`đ`/`dj`, `Џ`/`dž`/`dz`). `%`, `_`, and `\` are
  literal input, not SQL wildcards. Display/source text is never rewritten;
  descriptions never enter GeoJSON.
- Precision matches eligible **canonical-property winners**, not previous
  attempts. The table includes an auction if any winning property has that
  precision; the map includes only those matching properties. `NONE` means
  **no publishable location for the auction**, including missing/unknown
  location evidence: these rows remain in the table and there are no pins.

**Ресетуј** clears shared criteria, restores `not-ended` and page 1, and retains
sorting and selection. Sorting retains the current page. Pagination, selection,
reload, copied links, back/forward and refresh preserve unrelated applied state.
Back/forward restores the historical *criteria*, then evaluates time again.
The selected auction is retained even when inapplicable, with an explanation:
not in the local catalogue, outside filters, unmapped, outside viewport, or
excluded by the feature limit. Selecting it never clears filters or invents a
location. Filter edits never call RGZ, ingestion, or enrichment.

## Counts and refresh

The table is the global filtered population, paged by 25. Map panning affects
only the map subset; it never restricts the table. No “search this map area”
mode is offered. Distinguish:

1. filtered auction count (all pages);
2. mapped auction count in the viewport (distinct auction IDs, before limit);
3. feature count in the viewport and returned feature count (multiple genuine
   properties can belong to one auction);
4. filtered auctions without any publishable location;
5. filtered mapped auctions outside the viewport; and
6. truncation/returned-auction count when the feature limit excludes results.

`/api/auctions/view` refreshes the table fragment, map/sidebar and counts
atomically from one repeatable-read snapshot and one server `asOf` (UTC,
microsecond precision). It never replaces the form. Rapid edits cancel previous
requests and reject stale responses. Loading/errors retain the last usable
results and their displayed cutoff. Explicit source-refresh completion and
configured local automatic updates use the same path. Initial server-rendered
results remain usable before the map loads. All interactive responses are
private/no-store; standalone GeoJSON allows private reuse for 60 seconds.

## Canonical query and compatibility

The canonical parameters for `/`, `/api/map/auctions`, and `/api/auctions/view`
are the same:

| Field | Validation/default |
|---|---|
| `municipality` | Repeat for multiple choices, e.g. `municipality=Ада&municipality=Чачак`. Case-insensitive equality against any chosen name. Maximum 256 occurrences, 255 characters per name; blanks ignored, duplicates collapsed, canonical names sorted. Values must be in the packaged/retained catalogue. Absent/empty means unrestricted. Existing single supported/retained municipality links still work. |
| `placeName` | Exact raw-label match; optional, up to 255 characters. Retained values are suggested; an unknown safe value yields no match. |
| `category` | Exact supported/retained raw category; optional, up to 255 characters. |
| `status` | Case-insensitive matching, input canonicalized to supported/retained source spelling; original display/status spelling is unchanged. Optional, up to 255 characters. |
| `search` | Optional literal substring, up to 200 characters. |
| `minPrice`, `maxPrice` | Non-negative decimal RSD; up to 17 integer and 2 fractional digits; min <= max. No exponent notation. |
| `firstSale` | Blank/absent, `true`, or `false`. |
| `precision` | Case-insensitive `PARCEL`, `ADDRESS`, `STREET`, `CADASTRAL_MUNICIPALITY`, `SETTLEMENT`, `MUNICIPALITY`, `NONE`. |
| `timeScope` | `not-ended` (default), `ended`, `all`. |
| `from`, `to` | ISO local dates, years 0001..9998; inclusive start and exclusive next-day start, converted to UTC. |
| `sortBy` | `startingPrice` (default), `estimatedPrice`, `auctionNumber`, `endDate`, `startDate`, `id`. |
| `sortDir` | `asc` (default) or `desc`, case-insensitive. Nulls last, stable ascending ID tie-break. |
| `page` | Zero-based integer 0..1000000, default 0; fixed page size 25. |
| `auction` | Optional positive signed-64-bit auction ID. |

Canonical URLs contain explicit scope, sort and page, omit empty criteria, and
use UTF-8 form query encoding. **`asOf` is response metadata, never a URL
parameter**: bookmarks must not freeze relative time. Map transports additionally
require `bbox` and optionally `limit`; `/` rejects these transport fields.

`AuctionFilterParser` is the compatibility adapter. Accepted old aliases:

| Alias | Canonical field |
|---|---|
| `mapKind`, map API `kind` | `category` (raw, not normalized kind) |
| `mapStatus` | `status` |
| `mapPrecision` | `precision` |
| `mapFrom`, `mapTo` | `from`, `to` |

Aliases normalize through the same validators. Canonical and aliased values
must agree after trimming and enum/status case normalization; conflicting
values, including blank vs nonblank, return a field-specific 400. Repeated
parameters **other than `municipality`** (even identical), unknown fields, invalid
booleans/ranges/sorts and control/Unicode-format characters are rejected, never
silently dropped. Municipality selections use repeated parameters, not a
comma-separated string, and survive pagination, sorting, reload and history.

A legacy URL with **any end-date criterion and no explicit scope** normalizes
to `all`, preserving old historical `mapFrom`/`mapTo` and `from`/`to` API links.
An explicit scope always wins. Links with no temporal criteria, including the
original `category=Викендица&...&sortBy=startingPrice&sortDir=asc`, now visibly
default both projections to `not-ended`; select Ended/All without losing the
category. Normal browser use writes only canonical parameters.

Category/status options and validation use the union of safe retained raw
values and supported legacy seeds (`MapAuctionFilterOptions`), not only the
newest active source list. Newly retained values become available on refresh.
Unretained category/status values return a common 400 until supported/retained;
unsafe labels (control/format characters or angle brackets) are never options.
Raw labels are rendered as text. The existing `INVALID_MAP_REQUEST` problem
code/title remains on all three routes for compatibility; `field` identifies
the specific shared or spatial parameter. See [MAP_API.md](MAP_API.md).

## Ownership and non-goals

#44 is the correctness slice pulled out of #28. `AuctionFilters`,
`AuctionFilterParser`, `AuctionFilterSql`, and `PublishableLocationSql` are the
extension points for #28, **not a second model**. #28 still owns normalized
sale-scope/property-kind and KO controls, richer counterpart scrolling/camera
navigation and optional explicit shared spatial-search UX. #11 still owns
source lifecycle/absence auditing. Legacy source-snapshot backfill/new parcel
acquisition remains ingestion/enrichment work, not a filtering dependency.

The isolated `local-h2` transition profile has no shared spatial/UI contract;
use a PostgreSQL/PostGIS profile for this page.
