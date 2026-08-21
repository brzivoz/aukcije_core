#!/usr/bin/env python3
"""Stage 2 - crude KO + parcel + address extraction.

Deliberately not a parser. #18 and #19 own that. The only question here is
whether enough auctions carry a (KO, parcel) pair at all to make a registry
join worth building, so the patterns below cover the shapes actually observed
in the corpus and nothing else.

    python3 02_extract_refs.py
"""
import json
import re
import sys
from collections import Counter

from common import normkey, norm_parcel, out, read_json, write_json

# Observed parcel shapes, by frequency in the corpus:
#   парц.бр.2412 / парцели бр. 557/0 / парцела број 894 / кат. парцели бр. 210/3
#   број парцеле 2183 / Катастарске парцеле број 701 / к.п.бр. 123
#
# "број дела парцеле 1" and "део парцеле 2" are PART numbers within a parcel,
# not parcel numbers. They must never be captured; the lookbehind drops them.
_PART_PREFIX = r"(?<!дела )(?<!део )(?<!делу )(?<!дела)(?<!део)"

PARCEL_PATTERNS = [
    # ... парцел* [бр.|број] N[/M]
    _PART_PREFIX + r"парц\w*\.?\s*(?:бр\.?|број)?\s*\.?\s*(\d{1,6}(?:\s*/\s*\d{1,4})?)",
    # број парцеле N  (keyword order reversed)
    r"(?:бр\.?|број)\s*парцел\w*\s*(\d{1,6}(?:\s*/\s*\d{1,4})?)",
    # к.п. N / к.п.бр. N
    r"\bк\.?\s*п\.?\s*(?:бр\.?|број)?\s*(\d{1,6}(?:\s*/\s*\d{1,4})?)",
]

# КО as written in free text, to cross-check against the Place.Cadastral field.
KO_PATTERNS = [
    r"\bК\.?\s*О\.?\s*[:\-]?\s*([А-ЯЂЋЖЧШЊЉЏа-яђћжчшњљџ][\w\-]*(?:\s+[А-ЯЂЋЖЧШЊЉЏа-яђћжчшњљџ][\w\-]*){0,3})",
    r"\bK\.?\s*O\.?\s*[:\-]?\s*([A-ZČĆŽŠĐ][\w\-]*(?:\s+[A-ZČĆŽŠĐ][\w\-]*){0,3})",
]

# Trailing words that are sentence continuation, not part of a KO name.
KO_STOPWORDS = {
    "po", "kulturi", "povrsine", "upisana", "upisanih", "upisan", "broj",
    "br", "i", "u", "na", "sa", "opstina", "opstine", "ulica", "ulici",
    "privatna", "svojina", "list", "lista", "listu", "nepokretnosti", "to",
    "od", "kao", "zemljiste", "kat", "parcela", "parcele", "parceli", "ln",
}

_STREET_ROOT = r"(?:улиц\w*|ulic\w*)"
STREET_PATTERN = re.compile(
    _STREET_ROOT + r"\s+((?:[^\s,;.]+(?:\s+|$)){1,4}?)(?=(?:бр\.?|број|br\.?|broj)\s*\d|[,;.]|$)",
    re.IGNORECASE,
)
HOUSENO_PATTERN = re.compile(
    _STREET_ROOT + r"\s+[^,;]{0,80}?(?:бр\.?|број|br\.?|broj)\s*"
    r"(\d{1,4})([а-яa-z])?(?![\w/])",
    re.IGNORECASE,
)


def _first(patterns, text, flags=re.IGNORECASE):
    for pat in patterns:
        m = re.search(pat, text, flags)
        if m:
            return m.group(1)
    return None


def extract_parcels(text):
    if not text:
        return []
    found = []
    for pattern in PARCEL_PATTERNS:
        for match in re.finditer(pattern, text, re.IGNORECASE):
            parcel = norm_parcel(re.sub(r"\s+", "", match.group(1)))
            if parcel and parcel not in found:
                found.append(parcel)
    return found


def extract_parcel(text):
    parcels = extract_parcels(text)
    return parcels[0] if parcels else None


def extract_ko_from_text(text):
    if not text:
        return None
    raw = _first(KO_PATTERNS, text, flags=0)
    if not raw:
        return None
    words = raw.split()
    kept = []
    for w in words:
        if normkey(w) in KO_STOPWORDS:
            break
        kept.append(w)
    return " ".join(kept) if kept else None


def extract_street(text):
    if not text:
        return None, None
    street = None
    m = STREET_PATTERN.search(text)
    if m:
        cand = m.group(1).strip(" .,;“”\"")
        # drop a leading qualifier like 'потес'
        cand = re.sub(r'^(потес|место|насеље)\s+', "", cand, flags=re.IGNORECASE)
        street = cand or None
    hm = HOUSENO_PATTERN.search(text)
    house = (hm.group(1) + (hm.group(2) or "")) if hm else None
    return street, house


def main():
    corpus = read_json(out("corpus.json"))
    rows, stats = [], Counter()

    for a in corpus:
        det = a.get("_detalji") or {}
        place = det.get("Place") or {}
        desc = det.get("Description") or ""
        short = det.get("ShortDescription") or a.get("ShortDescription") or ""
        both = f"{desc}\n{short}"

        ko_field = place.get("Cadastral")
        ko_text = extract_ko_from_text(both)
        parcels_desc = extract_parcels(desc)
        parcels_short = extract_parcels(short)
        parcels = parcels_desc + [p for p in parcels_short if p not in parcels_desc]
        parcel_desc = parcels_desc[0] if parcels_desc else None
        parcel_short = parcels_short[0] if parcels_short else None
        parcel = parcels[0] if parcels else None
        parcel_source = ("Description" if parcel_desc
                         else "ShortDescription" if parcel_short else None)
        street, house = extract_street(both)

        row = {
            "id": a.get("Id"),
            "url": f"https://eaukcija.sud.rs/#/aukcije/{a.get('Id')}",
            "category": (det.get("Category") or {}).get("Name"),
            "ko_field": ko_field,
            "ko_text": ko_text,
            "ko_key": normkey(ko_field),
            "ko_agrees": bool(ko_field and ko_text
                              and normkey(ko_field) == normkey(ko_text)),
            "municipality": place.get("Municipality"),
            "settlement": place.get("Name"),
            "settlement_code": place.get("Code"),
            "place_parcel_number": place.get("ParcelNumber"),
            "parcel": parcel,
            "parcels": parcels,
            "parcel_source": parcel_source,
            "street": street,
            "house_number": house,
            "description": desc,
            "short_description": short,
        }
        rows.append(row)

        stats["total"] += 1
        stats["ko_field"] += bool(ko_field)
        stats["ko_text"] += bool(ko_text)
        stats["ko_agrees"] += row["ko_agrees"]
        stats["parcel"] += bool(parcel)
        stats["parcel_references"] += len(parcels)
        stats["multiple_parcels"] += len(parcels) > 1
        stats["parcel_in_description"] += bool(parcel_desc)
        stats["parcel_in_shortdesc_only"] += bool(parcel_short and not parcel_desc)
        stats["ko_and_parcel"] += bool(ko_field and parcel)
        stats["street"] += bool(street)
        stats["street_and_house"] += bool(street and house)
        stats["place_parcel_number"] += bool(place.get("ParcelNumber"))
        stats["neither_parcel_nor_street"] += not (parcel or street)

    write_json(out("refs.json"), rows)

    total = stats["total"]
    summary = {k: {"count": v, "pct": round(100.0 * v / total, 1)}
               for k, v in stats.items() if k != "total"}
    summary["total"] = total
    write_json(out("extraction_stats.json"), summary)

    print(f"{'metric':32s} {'count':>6s} {'pct':>7s}")
    print("-" * 48)
    for k, v in summary.items():
        if k == "total":
            continue
        print(f"{k:32s} {v['count']:6d} {v['pct']:6.1f}%")
    print("-" * 48)
    print(f"{'total':32s} {total:6d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
