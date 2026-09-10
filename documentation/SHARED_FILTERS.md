# Shared auction filters (#44, #56, #57)

#56 extends this same model with `since`, `sinceAt`, `publication`, `changeKind`
and `liveBidding` (native civil adapters: `sinceLocal`, `sinceOffset`). See the
[comparison API/coverage contract](MAP_API.md#changes-since-and-reviewed-revisions-56)
and [browser-local checkpoint/visit/review semantics](BROWSER_AND_FRONTEND.md#comparisons-and-local-review-state-56).
These intersect current membership, never replay historical filters. Clear Filters
removes comparison criteria but does not clear checkpoints/acknowledgements.
The explicitly labelled reviewed-auction panel is the only relaxed-scope view.

## Using the page

There is one Serbian-labelled filter form. The enhanced desktop workspace (#54)
starts with it closed; **Филтери** opens it beside the map/table, never as another
full-width block above them. Panel/disclosure preferences are remembered locally,
not in canonical URLs. Without JavaScript the native GET form remains visible.
**Примени филтере** applies its criteria to both views and returns the table to
page 1. Closing/reopening the panel preserves drafts; typing alone does not
change results. Source/background refresh uses applied state, not drafts.

Applied-filter chips remain visible in every mode, always including time scope.
A removable chip deliberately changes only that applied criterion, updates its
control, retains unrelated drafts/selection/sorting and resets the page. Removing
a non-default time scope restores `not-ended`; the default scope chip opens that
control instead of hiding the time restriction. **Непримењене измене** is visible
both inside the form and on its toggle while drafts differ. Numeric comparison
and chip labels preserve the full decimal RSD value without floating-point rounding.
Specialist status/precision/date/first-sale controls are under **Још филтера**,
with an applied-field count. Help is another disclosure. Native and server-side
validation reveal the affected panel/disclosure and make the invalid field reachable.
Reload/back-forward restore applied controls; unsaved drafts are not persisted.

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
- **Површина парцеле** (parcel size, #57) defaults to **Све површине** (All sizes).
  Presets compare exact square metres, without rounding: **< 8 ar** is `< 800 m²`,
  **8–15 ar** is `800 ≤ area ≤ 1,500 m²`, and **> 15 ar** is `> 1,500 m²`.
  **1 ar = 100 m²**; exactly 8 and 15 ar belong to the middle band.
  This is the **whole individual cadastral parcel area reported by RGZ**, not
  apartment/building floor area, a building footprint, the ownership share sold,
  or a summed auction lot. An auction appears once if **any** eligible individual
  parcel matches; the map shows only matching properties. Duplicate references
  to one parcel collapse, while genuine parcels keep their identity. Parcels in
  different bands can make the same auction match different presets.
  Category remains independent: a house or apartment auction can reference a
  large parcel. Combine the category control explicitly if desired.
  Coverage is incomplete. Missing, malformed, non-positive or stale area is
  **unknown, never zero**: All sizes keeps otherwise eligible auctions/properties,
  while numeric presets exclude unknown areas. No Unknown-size control, custom
  ranges, area sorting, acquisition or backfill is introduced.
- Starting-price bounds are inclusive **RSD**, with at most two decimal places.
  Blank first-sale means any; yes/no mean true/false, not presence/absence.
- Text search covers auction number, short description and description only.
  Cyrillic and Serbian Latin share a case/diacritic-insensitive search spelling
  (`Љ`/`lj`, `Њ`/`nj`, `Ђ`/`đ`/`dj`, `Џ`/`dž`/`dz`). `%`, `_`, and `\` are
  literal input, not SQL wildcards. Display/source text is never rewritten;
  descriptions never enter GeoJSON. Typing `< 8ar` here still searches that
  literal substring; use **Површина парцеле** for numeric area comparisons.
- Precision matches eligible **canonical-property winners**, not previous
  attempts. The table includes an auction if any winning property has that
  precision; the map includes only those matching properties. `NONE` means
  **no publishable location for the auction**, including missing/unknown
  location evidence: these rows remain in the table and there are no pins.
  When combined with parcel size, **both predicates must hold on the same
  winner**, not two different properties of the auction. `NONE` plus a size
  band is therefore empty. Winners and suppression of coarse fallbacks are
  determined **before** size, precision and viewport filters: a nonmatching
  replacement never revives a historical/losing attempt or hidden centroid.

**Ресетуј** clears shared criteria, restores `not-ended` and page 1, and retains
sorting and selection. Sorting retains the current page. Pagination, selection,
reload, copied links, back/forward and refresh preserve unrelated applied state.
Back/forward restores the historical *criteria*, then evaluates time again.
The selected auction is retained even when inapplicable, with an explanation:
not in the local catalogue, outside filters, unmapped, outside viewport, or
excluded by the feature limit. Selecting it never clears filters or invents a
location. Filter edits never call RGZ, ingestion, or enrichment.

## Selection and map details (#46)

Selection and transient details are independent. Click a map object or its
result/table button to select it and open non-modal details. With a keyboard,
use Enter or Space on a result/table button, **Отвори детаље** in the selection
summary, or the compact **Избор** control. Since #54, Map + results shows details
inside the rail; Map-only reuses the same article in a popup. The selection summary
is also in the rail, never above the map. Dismissal hides details and the summary;
auction identity and map/list/table highlighting remain. The compact selection
control stays available for deliberate reopening, including an explicit reason
when the selected property is unavailable. No separate detail/query state is added.

- **Escape**, **Назад на резултате** in the rail, **×** in the popup (accessible
  name **Затвори детаље аукције**, 44×44 px), or a
  blank-map/outside click closes both details surfaces. Content and safe
  source-link clicks do not close them. The eAukcija and Google Maps links are
  displayed on separate lines. Activating the same or another object opens it.
- Keyboard opening focuses the details article's safe source link, or the labelled
  article itself if no allowlisted link exists. There is no modal focus trap. Escape
  and × return focus to the connected, visible opening control where possible;
  if a result was replaced, a table/summary trigger hidden, or a cluster choice
  removed, use the current matching property result, then the map canvas.
  Pointer click-away never returns focus from the clicked control. Background
  refresh never transfers focus into details; open popup/summary controls remain
  connected and focused result/table positions are retained where applicable.
- Panning/zooming, periodic updates, source-refresh completion and source/layer
  redraws do not reopen dismissed details or clear criteria/selection. Within
  the page, the selected property uses the existing GeoJSON feature ID, not the
  first property of the same auction. If it is unavailable, keep its identity
  and explain the absence, without substituting another property.
- The URL stores **only the auction identity**, not a property DOM reference or
  open/dismissed state. Reload/copied links and back/forward restore the auction
  and criteria **with the popup closed**. The restored selection summary is
  initially available for reopening or explaining an unavailable selection;
  Escape/click-away can dismiss that summary too, even without a popup.
  An available auction initially selects its first returned property;
  explicitly select another if needed. Closing or
  reopening the same selection creates no history entry or query parameter.
  Back/forward is not an undo stack for transient popup visibility.

Municipality and other native disclosures retain their own Escape/outside-click
behavior; these are not modal dialogs. Operator confirmations/cancellation and
future durable property-identity/keyed-results reconciliation are outside #46.

## Counts and refresh

A compact disclosure beside the map controls shows global filtered auctions and
viewport auction/property totals. Its breakdown distinguishes returned properties
and auctions, unmapped and outside-view auctions. **Прикажи све у табели** switches
to the global table without applying drafts. Freshness, failures and truncation
stay visible outside disclosures, including Map-only. A recoverable view failure
has **Поново учитај приказ**; it retries the local view, never source enrichment.
Background loading retains existing results and does not add a vertical banner.

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
| `parcelSize` | Absent/blank: unrestricted. Otherwise exactly `under-8`, `8-15`, or `over-15` (case-sensitive, surrounding spaces trimmed). Unsupported or repeated values, even identical/blanks, return a field-specific 400. |
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

## Retained parcel-area evidence (#57)

`PublishableLocationSql` projects a nullable PostgreSQL `numeric` area from the
**current selected RGZ attempt's** `candidate_evidence.areaSquareMetres`, then
selects canonical-property winners without size restrictions. Only positive
JSON **numbers** on resolved RGZ parcel attempts qualify; strings (even numeric
strings), objects, arrays, null and missing keys are unknown. The guarded cast
cannot interpret malformed evidence as zero or throw a numeric parsing error.
Current extraction membership, publishable reference status and the exact current
KO-match premise remain mandatory. Unrelated or historical cache rows do not
establish auction membership; retained provenance is neither rewritten nor sent
to the browser. There is no new freshness TTL or description-area extraction.

`AuctionFilterSql.propertyPredicate` is shared by auction membership, map features,
counts/selection and table precision labels. Filtering is parameterized and local;
no filter request invokes RGZ, source clients, enrichment or synchronous backfill.
The initial implementation reuses JSON evidence without a schema migration. See
[the API's query-plan discussion](MAP_API.md#publication-deduplication-spatial-bounds-and-privacy)
and [#57 verification](2026-09-09-issue-57-verification.md).

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
