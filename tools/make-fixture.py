#!/usr/bin/env python3
"""Build a slim, git-committable test fixture from the scraper's aukcije.json.

The raw scraper output is ~28 MB, almost entirely base64 `Thumbnail` blobs.
This keeps a stratified sample with thumbnails stripped so parser work
(EPIC-02) has realistic input in the repo.

    python3 tools/make-fixture.py <raw aukcije.json> <output.json>
"""
import json
import re
import sys

DROP_KEYS = {"Thumbnail", "ThumbnailType"}
PARCEL = re.compile(
    r"(?:парц\w*|кат\w*|к\.?\s*п\.?|kp|parc\w*|kat\w*)[^\d]{0,30}(\d+(?:/\d+)?)", re.I
)
ADDRESS = re.compile(r"(улиц\w+|ул\.\s*|улици)", re.I)
PER_BUCKET = 40


def strip(record):
    out = {k: v for k, v in record.items() if k not in DROP_KEYS}
    detail = out.get("_detalji")
    if isinstance(detail, dict):
        out["_detalji"] = {k: v for k, v in detail.items() if k not in DROP_KEYS}
    return out


def bucket(record):
    detail = record.get("_detalji") if isinstance(record.get("_detalji"), dict) else {}
    text = f"{record.get('ShortDescription') or ''}\n{detail.get('Description') or ''}"
    if PARCEL.search(text):
        return "parcel"
    return "address" if ADDRESS.search(text) else "unresolvable"


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)

    with open(sys.argv[1], encoding="utf-8") as handle:
        records = json.load(handle)

    buckets = {"parcel": [], "address": [], "unresolvable": []}
    for record in records:
        target = buckets[bucket(record)]
        if len(target) < PER_BUCKET:
            target.append(strip(record))

    sample = buckets["parcel"] + buckets["address"] + buckets["unresolvable"]
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        json.dump(sample, handle, ensure_ascii=False, indent=2)

    counts = ", ".join(f"{name}={len(items)}" for name, items in buckets.items())
    print(f"wrote {len(sample)} records to {sys.argv[2]} ({counts})")


if __name__ == "__main__":
    main()
