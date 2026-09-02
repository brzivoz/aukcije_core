#!/usr/bin/env python3
"""Materialize the reviewed issue-18 fixture from the private #32 capture.

The input stays ignored. This command writes JSON only to stdout so a reviewer
can inspect it before committing through the normal patch workflow.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path


HELD_OUT = {
    66413, 179104, 179181, 179368, 180293, 180865, 181003, 181021,
    181104, 181561, 179642, 181541, 179620, 181085, 181323,
}
NEGATIVE = {
    179646, 179642, 179099, 181541, 179376, 179705, 180260, 179434,
    181085, 181323, 179620, 179613, 181617, 179129, 179161, 179164,
    179165, 179186, 180894, 181126,
}

# id | official contextual KO | official KO code | reviewed references
# Reference fields are type, pattern shorthand, exact raw needle, typed value.
# Address token values use ~ as an internal separator. Repeated enumeration
# evidence is intentional: one scoped phrase supports four parcel identities.
SPECS = r"""
66390|МАРТОНОШ|805840|P,PA,парц.бр.2412,2412;K,KA,К.О.Мартонош,;L,LA,ЛН 3133,3133
66413|НОВИ КНЕЖЕВАЦ|801941|P,PA,парц.бр.3268/1,3268/1;K,KA,К.О.Нови Кнежевац,;L,LA,ЛН 4797,4797
178921|БОГОШТИЦА|720151|P,PL,катастарска парцела број 288,288;L,LL,лист непокретности број 494,494;K,KL,КО Богоштица,
178851|ВАПА|739324|P,PL,парцела број 1171/0,1171/0;K,KTA,KO Вапа,;L,LA,ЛН број 240,240
178923|ЗЕОКЕ|726648|P,PA,Кп.бр. 1196,1196;K,KTA,KO Зеоке,;L,LL,лист непокретности број 76,76
179049|БАГАЧИЋЕ|739251|P,PL,парцела број 66,66;K,KTA,KO Багачиће,;L,LL,лист непокретности 22,22
179006|ЛАЋАРАК|804169|P,PA,парц.бр. 2779/2,2779/2;L,LA,ЛН 4020,4020;K,KL,КО Лаћарак,
179104|ГОРЊА БЕЛА РЕКА|715760|P,PA,К.П.бр.7475,7475;A,AL,Улица / Потес: ПУЈЧИНА ПАДИНА,ПУЈЧИНА ПАДИНА;L,LA,Л.Н.бр.183,183;K,KA,К.О. Горња Бела Река,
179119|БОЉЕВАЦ|704890|P,PL,Број парцеле: 1145,1145
179131|НАВАЛИН|725463|A,AS,улица Економија,Економија;P,PA,кп.бр. 26,26;L,LL,Лист непокретности број 144,144;K,KL,КО Навалин,
179168|ЗАГРАЂЕ|715816|P,PL,Број парцеле: 95,95
179173|ВЕЛИКА ДРЕНОВА|741531|P,PL,Кат.парцела број 5734,5734
179181|ВЕЛИКА ДРЕНОВА|741531|P,PM,Кат.прцела 331,331
179304|РАСТОВНИЦА|715514|P,PA,к.п. бр. 1386,1386;K,KL,КО Растовница,;L,LA,ЛН бр. 234,234
179368|НОВИ СЛАНКАМЕН|805343|P,PA,Кат.парц.бр. 4384,4384;P,PA,Кат.парц.бр.  4385,4385;P,PA,Кат.парц.бр. 4386,4386;A,AL,Улица / Потес: ЈУРАЈА ВИТЕЗА,ЈУРАЈА ВИТЕЗА;A,AL,Улица / Потес: КОШЕВАЦ,КОШЕВАЦ;L,LL,листу непокретности бр. 897,897;K,KA,К.О. Нови Сланкамен,
179377|ВОЈКА|805483|P,PA,парц.бр. 4364/24,4364/24;L,LL,Листу непокретности број 4698,4698;K,KL,КО Војка,
179553|ВЛАСОТИНЦЕ-ВАРОШ|710326|A,AH,ул. Синише Јањића 7,Синише Јањића~7;P,PA,кп.бр.2503/1,2503/1;K,KL,КО Власотинце-Варош,
179945|СЕПЦИ|736660|P,PA,к.п.бр. 593/1,593/1;L,LL,лист непокретности бр. 1087,1087;K,KL,КО Сепци,
180145|ВАЛАКОЊЕ|704903|P,PA,КП. бр. 2650/1,2650/1;K,KL,КО ВАЛАКОЊЕ,
180277|РЕКОВАЦ|737780|A,AL,Адреса: Његошева,Његошева;K,KL,КО: Рековац,;L,LL,Број листа непокретности: 1307,1307;P,PL,Број парцеле: 2847/4,2847/4
180289|ГОРЊА КРАВАРИЦА|726516|P,PA,кп. бр. 1630,1630;K,KL,КО Горња Краварица,;L,LL,лист непокретности бр. 51,51
179300|МУР|731021|P,PL,парцела број 2041/1,2041/1;K,KTA,KO Мур,;L,LL,лист непокретности 1698,1698
180293|КУЗМИН|804142|P,PT,KP 1928,1928;K,KT,KO KUZMIN,
180358|ЈАКОВО|716081|A,AH,ул. Липовачка 28,Липовачка~28;P,PL,катастарској парцели бр. 229/21,229/21;K,KL,КО Јаково,
180210|СРЕЗОЈЕВЦИ|713279|P,PA,Кп.бр. 495/3,495/3;K,KTA,KO Срезојевци,;L,LL,лист непокретности број 417,417
180635|ВРАЊЕ I|711241|A,AH,ул. Косовке девојке бр. 2,Косовке девојке~2;P,PA,КП.бр.10941/4,10941/4
180702|БРЕСТОВАЦ|724637|P,PA,Кп.бр. 2672,2672;A,AS,улица село кућни плац,село кућни плац
180865|НИШ БУБАЊ|729795|A,AH,ул. Милентијева бр. 24,Милентијева~24;P,PA,кп. бр. 449,449;K,KL,КО Ниш Бубањ,
180928|ДЕКУТИНЦЕ|709697|A,AS,ул. Шупље дрво,Шупље дрво;P,PA,КП.бр.9,9;L,LA,ЛН 87,87;K,KL,КО Декутинце,
181000|ГОРЊЕ КРАЈИНЦЕ|724823|A,AL,Адреса: НЕРЕЗИНЕ,НЕРЕЗИНЕ;K,KL,КО: Горње Крајинце,;P,PL,Број парцеле: 330 /2,330/2
181003|ГОРЊЕ КРАЈИНЦЕ|724823|A,AL,Адреса: НЕРЕЗИНЕ,НЕРЕЗИНЕ;K,KL,КО: Горње Крајинце,;P,PL,Број парцеле: 331 /1,331/1
181007|ГОРЊЕ КРАЈИНЦЕ|724823|A,AL,Адреса: ЖАБАР,ЖАБАР;K,KL,КО: Горње Крајинце,;P,PL,Број парцеле: 2562,2562
181017|ДОЛОВО|802379|P,PL,катастарска парцела број 870/2,870/2;P,PL,катастарска парцела број 871/2,871/2;K,KL,КО Долово,
181021|БАНАТСКО НОВО СЕЛО|802336|P,PE,__ENUM__,1900;P,PE,__ENUM__,1901;P,PE,__ENUM__,1902;P,PE,__ENUM__,1903;K,KL,КО Банатско Ново Село,;A,AH,ул. Змај Јовина бр. 11,Змај Јовина~11
181024|ЗРЕЊАНИН III|805777|P,PA,КП број 1577,1577
181026|ЗРЕЊАНИН III|805777|P,PA,КП број 2122,2122
181104|ВЕЛИКА ПЛАНА I|708585|A,AH,улица Булевар Ослобођења 109,Булевар Ослобођења~109;P,PL,катастарској парцели 4411/2,4411/2;K,KL,КО Велика Плана I,;P,PA,кп.бр. 4411/20,4411/20;L,LA,ЛН бр. 10854,10854
181325|ОСЕЧЕНИЦА|728144|P,PL,кат.парцели број 2479,2479;K,KL,КО Осеченица,;L,LL,лист непокретности број 745,745
181561|БРЗА|724645|A,AL,Адреса: СИПУТ ЛОЈЗЕ,СИПУТ ЛОЈЗЕ;K,KL,КО: Брза,;P,PL,Број парцеле: 2763,2763
181652|ЛОК|804665|L,LL,лист непокретности 52,52;K,KA,К.О. Лок,;P,PL,парцели број 529,529;P,PL,парцели број 530,530
"""

PATTERNS = {
    "PA": "PARCEL_ABBREVIATED", "PL": "PARCEL_LABELED",
    "PT": "PARCEL_LATIN_ABBREVIATED", "PE": "PARCEL_ENUMERATION",
    "PM": "PARCEL_MALFORMED_LABEL", "KA": "KO_ABBREVIATED",
    "KL": "KO_LABELED", "KT": "KO_LATIN",
    "KTA": "KO_LATIN_ABBREVIATED",
    "LA": "LAND_REGISTER_ABBREVIATED", "LL": "LAND_REGISTER_LABELED",
    "AL": "ADDRESS_LABELED", "AH": "ADDRESS_STREET_HOUSE",
    "AS": "ADDRESS_STREET_ONLY",
}
TYPES = {"P": "PARCEL", "K": "CADASTRAL_MUNICIPALITY",
         "L": "LAND_REGISTER", "A": "ADDRESS"}

TRAPS = {
    179104: ["Бр.дела парцеле: 1"], 179119: ["Број дела парцеле: 1"],
    179131: ["број дела парцеле 1"], 179168: ["Број дела парцеле: 1"],
    180277: ["Број дела парцеле: 1"], 180280: ["Број дела парцеле: 1"],
    180358: ["површине парцеле 786 квм", "број дела парцела 1"],
    180702: ["број дела 2"], 181007: ["Број дела парцеле: 1"],
    181325: ["подброј парцеле 60"], 181652: ["број дела 1"],
}
ADJUDICATIONS = {
    179181: ["adj-179181-malformed-label"],
    179368: ["adj-179368-ko-conflict"],
    180358: ["adj-180358-area-not-parcel"],
    181021: ["adj-181021-enumeration"],
    181325: ["adj-181325-subnumber"],
}

def parse_specs():
    parsed = {}
    for line in SPECS.strip().splitlines():
        auction_id, ko_name, ko_code, encoded = line.split("|", 3)
        references = []
        for value in encoded.split(";"):
            ref_type, pattern, needle, typed_value = value.split(",", 3)
            if needle == "__ENUM__":
                needle = "к.п. 1900, 1901, 1902 и 1903"
            references.append((TYPES[ref_type], PATTERNS[pattern], needle,
                               typed_value))
        parsed[int(auction_id)] = (ko_name, ko_code, references)
    return parsed


def load_snapshot_hashes(selection_query):
    rows = re.findall(r"\((\d+),\s*'([0-9a-f]{64})'", selection_query.read_text())
    return {int(auction_id): digest for auction_id, digest in rows}


def field_hash(value):
    return hashlib.sha256((value or "").encode()).hexdigest()


def exact(source, needle):
    pattern = re.escape(needle).replace(r"\ ", r"\s+")
    match = re.search(pattern, source, re.IGNORECASE)
    if not match:
        raise ValueError(f"reviewed evidence is absent: {needle!r}")
    return match.group(0)


def make_record(row, specifications, snapshot_hashes):
    auction_id = row["Id"]
    detail = row["_detalji"]
    description = detail.get("Description") or ""
    split = "HELD_OUT" if auction_id in HELD_OUT else "DEVELOPMENT"
    evidence = []
    evidence_indexes = {}

    def add_evidence(text):
        if text not in evidence_indexes:
            evidence_indexes[text] = len(evidence)
            evidence.append({"sourceField": "detail.Description", "text": text})
        return evidence_indexes[text]

    expected = []
    if auction_id in specifications:
        ko_name, ko_code, references = specifications[auction_id]
        for number, (ref_type, pattern, needle, typed_value) in enumerate(
                references, start=1):
            raw = exact(description, needle)
            if ref_type == "ADDRESS":
                typed_value = typed_value.split("~")
            expected.append({
                "annotationId": f"a{auction_id}-r{number:02d}",
                "type": ref_type,
                "pattern": pattern,
                "evidenceIndex": add_evidence(raw),
                "rawEvidence": raw,
                "koName": ko_name,
                "koCode": ko_code,
                "parcelNumber": typed_value if ref_type == "PARCEL" else None,
                "landRegisterNumber": typed_value
                if ref_type == "LAND_REGISTER" else None,
                "addressTokens": typed_value if ref_type == "ADDRESS" else None,
            })
        for needle in TRAPS.get(auction_id, []):
            add_evidence(exact(description, needle))
    else:
        add_evidence(description.strip()[:240])

    retained_text = " ".join(item["text"] for item in evidence)
    tags = set()
    if re.search(r"[\u0400-\u04ff]", retained_text):
        tags.add("CYRILLIC")
    latin_tokens = re.findall(r"[A-Za-zČĆŽŠĐčćžšđ]{2,}", retained_text)
    if any(not re.fullmatch(r"[IVXLCDM]+", token, re.IGNORECASE)
           for token in latin_tokens):
        tags.add("LATIN")
    if not expected:
        tags.update({"NEGATIVE", "MISSING_FIELDS"})
    if len(expected) > 1:
        tags.add("MULTIPLE_REFERENCES")
    if any(item["parcelNumber"] and "/" in item["parcelNumber"]
           for item in expected):
        tags.add("PARCEL_SUFFIX")
    if any(item["type"] == "LAND_REGISTER" for item in expected):
        tags.add("LAND_REGISTER")
    if any(item["type"] == "ADDRESS" for item in expected):
        tags.add("ADDRESS")
    if auction_id in {179181, 179368, 181104, 181325, 181323}:
        tags.add("MALFORMED_PROSE")
    if auction_id in {179119, 179168, 179173, 179181, 180635, 180702,
                      181024, 181026}:
        tags.add("MISSING_FIELDS")
    if auction_id in TRAPS or auction_id in {
            179620, 179613, 181617, 180260, 180894}:
        tags.add("FALSE_POSITIVE_TRAP")

    return {
        "auctionId": auction_id,
        "snapshotSha256": snapshot_hashes[auction_id],
        "split": split,
        "category": detail["Category"]["Name"],
        "caseStatus": "REFERENCES_PRESENT" if expected
        else "NO_DESCRIPTION_REFERENCE",
        "patternTags": sorted(tags),
        "sourceFieldHashes": {
            "descriptionSha256": field_hash(detail.get("Description")),
            "shortDescriptionSha256": field_hash(detail.get("ShortDescription")),
        },
        "evidence": evidence,
        "expectedReferences": expected,
        "reviewStatus": "ADJUDICATED",
        "adjudicationIds": ADJUDICATIONS.get(auction_id, []),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture", type=Path,
                        default=Path("spike/issue-32/out/corpus.json"))
    parser.add_argument("--split", choices=["DEVELOPMENT", "HELD_OUT"],
                        required=True)
    parser.add_argument("--selection-query", type=Path,
                        default=Path("corpus/property-references/v1/selection.sql"))
    args = parser.parse_args()

    rows = json.loads(args.capture.read_text())
    indexed = {row["Id"]: row for row in rows}
    specifications = parse_specs()
    snapshot_hashes = load_snapshot_hashes(args.selection_query)
    selected_ids = set(specifications) | NEGATIVE
    if (len(selected_ids) != 60 or not selected_ids.issubset(indexed)
            or not selected_ids.issubset(snapshot_hashes)):
        raise SystemExit("the frozen 60-auction selection is unavailable")
    records = [make_record(indexed[auction_id], specifications, snapshot_hashes)
               for auction_id in selected_ids]
    if sum(len(record["expectedReferences"]) for record in records) != 118:
        raise SystemExit("the reviewed 118-reference contract changed")
    if sum(not record["expectedReferences"] for record in records) != 20:
        raise SystemExit("the reviewed 20-negative contract changed")

    output = {
        "schemaVersion": "property-reference-corpus-v1",
        "corpusVersion": "2026-09-02.2",
        "split": args.split,
        "auctions": sorted(
            [record for record in records if record["split"] == args.split],
            key=lambda record: record["auctionId"]),
    }
    print(json.dumps(output, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
