# Municipality dropdown catalogue

`serbia-municipalities.tsv` contains all 168 `MUNICIPALITY` codes/names from the
retained RGZ Address Registry extract. It is packaged with the application so
municipalities with no auctions are available offline, without requiring an
operator to import a registry artifact first. Only public names/codes are
included, not addresses, geometry or auction data.

Source/provenance:

- Publisher: Republički geodetski zavod (RGZ), **Adresni registar**.
- Resource: https://data.gov.rs/sr/datasets/r/be7c80e3-206b-46af-b31d-4b9f6ae596f9
- Dataset date: 2026-08-22; license: `sodl`, **Srpska licenca za otvorene podatke**.
- Source archive SHA-256: `3e601009bc1c540c83e2396af703a51713edb508d3fb220d5e6e36a52e4e0f15`.
- `kucni_broj.gpkg` SHA-256: `ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3`.
- Derived `centroids.ndjson` SHA-256: `162112e9fb2cb6ae22ff0a9b922cabcf454c393243524a28598e541203d26c5b`.

Derivation: select `level == MUNICIPALITY`, retain `officialCode` and
`nameCyrillic`, sort by official code, and change uppercase names to display
title case (`на` remains lowercase). No identities or geographic coverage were
invented: the catalogue has the coverage of that pinned registry extract, not
an independently maintained list of every historic administrative unit.

The dropdown merges this list with safe retained portal municipality labels.
Case variants collapse; distinct raw spellings such as a `-град` suffix remain
separate rather than being guessed aliases. Filtering is case-insensitive
name equality against any selected municipality. Original auction text stays
unchanged. Refresh this file from a reviewed registry version, preserving this
attribution and updating provenance/tests, rather than from live filter requests.
