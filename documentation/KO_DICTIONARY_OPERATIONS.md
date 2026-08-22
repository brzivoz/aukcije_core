# Canonical KO dictionary operations

Issue [#14](https://github.com/brzivoz/aukcije_core/issues/14) builds the
canonical cadastral-municipality (KO) dictionary and normalized lookup index.
It is a database-free operator action. Its input is the active immutable
centroid extract from #36, which already retains the exact official KO,
settlement, and municipality identifiers/names and the Address Registry source
hash/date. Requiring the full #22 PostGIS import here would contradict the
coarse-map dependency chain `#36 → #14 → #37`; #22 remains the later address
and parcel-resolution input.

## Published contract

The defaults use the git-ignored runtime directories below:

```text
data/address-registry-centroids/
└── ACTIVE                         source #36 version

data/address-registry-ko-dictionary/
├── ACTIVE
├── versions/
│   └── <source-date>-<gpkg-sha256>-aliases-<alias-sha256>/
│       ├── manifest.json
│       ├── ko-dictionary.ndjson
│       ├── normalized-index.ndjson
│       ├── report.json
│       ├── alias-overrides.json
│       └── ATTRIBUTION.md
└── runs/
    └── <started-epoch-millis>-<run-id>.json
```

Every dictionary and index row repeats the dictionary version, source dataset
date, and official GPKG SHA-256. `manifest.json` format version 2 additionally records the
source ZIP/GPKG/schema hashes, hashes of the #36 manifest and centroid file,
source row accounting, the complete alias-data version/hash, an independently
reproducible municipality-alias hash, separate record-kind counts, content
counts, normalizer contract, and the size/hash of every immutable output file.
The publisher status path and runtime loader reject legacy manifest format 1
explicitly rather than interpreting missing format-2 fields as corruption.

`ko-dictionary.ndjson` is ordered by KO code. Each entry retains the official
Cyrillic and Latin name forms, all normalized forms, resolved municipality
records and their reviewed municipality-alias ids, resolved settlement records
and their municipality relationships, and any reviewed KO-alias records.
Multiple official parent relationships remain explicit arrays; publication
never chooses one by row order.

`normalized-index.ndjson` is ordered by normalized name. Each candidate lists
the KO code, all municipality codes, whether the match is an official name,
and the review-record ids for matching aliases. Multiple candidates are
preserved so #33 and #37 can refuse ambiguity rather than guess. The duplicate
report intentionally follows #14's cross-municipality wording; the index is the
authority for every collision, including multiple KO candidates inside one
municipality.

## Shared normalization contract

Both artifact construction and downstream query matching must call
`SerbianNameNormalizer.normalize`. Contract `serbian-name-v1` applies Unicode
compatibility normalization, Serbian Cyrillic→Latin folding, locale-independent
case folding, punctuation removal, and spacing collapse. It intentionally
normalizes examples such as `Чајетина` and `Čajetina` to the same key.

The manifest names the normalizer contract. Copying this logic into #33 or #37
would create a divergent index/query contract and is a defect.

## Reviewed KO and municipality alias data

The version-controlled source is
`config/address-registry/ko-alias-overrides.json`. Format version 2 keeps KO
names and municipality identities in separate arrays. A municipality
equivalence is never converted into one alias per KO and is never implemented
as a generic suffix rule.

KO-name records live under `koAliases`:

```json
{
  "recordKind": "KO_ALIAS",
  "id": "stable-review-record-id",
  "koCode": "official KO code",
  "name": "historical or colloquial name",
  "normalizedName": "SHARED NORMALIZER OUTPUT",
  "kind": "HISTORICAL",
  "provenance": "what establishes this alias",
  "sourceReference": "stable document, URL, or archive reference",
  "reviewer": "accountable reviewer identity",
  "reviewedAt": "2026-08-22"
}
```

Municipality records live under `municipalityAliases`:

```json
{
  "recordKind": "MUNICIPALITY_ALIAS",
  "id": "stable-review-record-id",
  "municipalityCode": "official municipality code",
  "name": "portal municipality form",
  "normalizedName": "SHARED NORMALIZER OUTPUT",
  "provenance": "what establishes this equivalence",
  "sourceReference": "stable evidence reference",
  "reviewer": "accountable reviewer identity",
  "reviewedAt": "2026-08-22"
}
```

`kind` on a KO alias is `HISTORICAL` or `COLLOQUIAL`. Missing review/provenance
fields, future review dates, duplicate ids across either record kind, unusable
names, stored normalization drift, and unknown KO or municipality targets fail
before publication. A municipality alias target must also be referenced by at
least one KO row, because only those municipality records are emitted. If a
normalized alias denotes multiple official municipalities across reviewed
aliases, or conflicts with an official municipality name belonging to another
code, publication retains the complete collision and #37 refuses to use it for
automatic selection.

The publisher hashes the complete review dataset and the canonical
municipality-alias subset independently. An alias change creates a new
immutable dictionary version even when the Address Registry snapshot is
unchanged. The manifest, report, canonical alias file, and applicable
dictionary relationships keep the two record kinds distinct.

## Build from a reviewed active centroid extract

First build #36 as described in
[CENTROID_EXTRACT_OPERATIONS.md](CENTROID_EXTRACT_OPERATIONS.md). Pin its
reviewed official GPKG hash when building the dictionary:

```bash
export ADDRESS_REGISTRY_KO_DICTIONARY_CENTROID_DIRECTORY="$PWD/data/address-registry-centroids"
export ADDRESS_REGISTRY_KO_DICTIONARY_PUBLISH_DIRECTORY="$PWD/data/address-registry-ko-dictionary"
export ADDRESS_REGISTRY_KO_DICTIONARY_ALIAS_OVERRIDES="$PWD/config/address-registry/ko-alias-overrides.json"
export ADDRESS_REGISTRY_KO_DICTIONARY_EXPECTED_GPKG_SHA256='<reviewed #36 GPKG SHA-256>'
./gradlew buildKoDictionary
```

The default KO magnitude gate is 3,500–6,000 entries. Override
`ADDRESS_REGISTRY_KO_DICTIONARY_MINIMUM_KO_ENTRIES` and
`ADDRESS_REGISTRY_KO_DICTIONARY_MAXIMUM_KO_ENTRIES` only after a reviewed
source-contract change, never merely to force a failing refresh through.

The result JSON reports the source/dictionary/alias versions, KO and duplicate
counts, aliases applied, rejected source rows, artifact size, phase durations,
active version before the build, and final version directory. Rebuilding fixed
source and alias data returns `UNCHANGED` only after comparing every output
filename and byte.

## Status and retained evidence

Status requires neither the source artifact nor a database:

```bash
export ADDRESS_REGISTRY_KO_DICTIONARY_ACTION=STATUS
export ADDRESS_REGISTRY_KO_DICTIONARY_PUBLISH_DIRECTORY="$PWD/data/address-registry-ko-dictionary"
./gradlew buildKoDictionary
```

The deterministic report lists total KO entries, all cross-municipality
duplicate official-name groups and their exact codes, separate KO and
municipality alias counts, municipality-alias collisions, source rows rejected
by reason, and the named validation gates passed. Runtime ids,
timestamps, durations, outcomes, and stable failure codes live only under
`runs/`, so operational evidence does not make the immutable artifact
nondeterministic.

## Validation, immutability, and failure recovery

The publisher takes the #36 publication lock while reading its `ACTIVE`
version and independently verifies the source manifest, every listed file
size/hash, row accounting, provenance on every centroid line, and per-level
counts. It then validates:

- the configured KO row-count magnitude;
- one unique output entry per official KO code;
- usable official names and normalized keys;
- every KO→settlement, KO→municipality, and settlement→municipality reference;
- reviewed KO and municipality alias schema, stored normalization, provenance,
  reviewer, date, globally unique id, target, and an emitted KO relationship;
- explicit retention of municipality-alias collisions across reviewed aliases
  and official municipality names; and
- the deterministic duplicate-name and normalized-index output.

Publication takes a separate dictionary lock, prunes abandoned staging,
writes a complete sibling staging directory, atomically moves a new version,
and atomically replaces `ACTIVE` last. A checksum, truncation, null/name,
count, relationship, alias-review, output-byte, or filesystem-atomicity failure
therefore leaves the prior active dictionary in place. Existing version bytes
are never overwritten; a different rebuild of the same version fails with
`IMMUTABLE_VERSION_CONFLICT`.

Before either moving a version or changing `ACTIVE`, publication compares the
candidate source dataset date with the active dictionary's source date. An
older source fails with `SOURCE_DATE_DOWNGRADE`, including when the older
version already exists and would otherwise be an `UNCHANGED` replay. This
protects against restored or staging copies of an older #36 tree. The guard
compares source dates only: reverting to an earlier reviewed alias dataset for
the same source date remains an explicit, supported pointer change.

Generated versions remain under the ignored `data/` tree. Commit the reviewed
alias source, application code, tests, operations guide, and verification
record—not official or generated bulk artifacts.
