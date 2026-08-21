"""Shared helpers for the #32 spike. Throwaway code - do not import from src/."""
import json
import os
import re
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
FIXTURE = os.path.join(REPO, "src/test/resources/fixtures/auctions-sample.json")

os.makedirs(OUT, exist_ok=True)


def out(name):
    return os.path.join(OUT, name)


def read_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=2)
    return path


# --- script / case normalization -------------------------------------------
# Crude on purpose. #18 and #19 own the real normalizer; this only has to be
# good enough to decide whether a join is worth building.

_CYR_TO_LAT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "ђ": "dj", "е": "e",
    "ж": "z", "з": "z", "и": "i", "ј": "j", "к": "k", "л": "l", "љ": "lj",
    "м": "m", "н": "n", "њ": "nj", "о": "o", "п": "p", "р": "r", "с": "s",
    "т": "t", "ћ": "c", "у": "u", "ф": "f", "х": "h", "ц": "c", "ч": "c",
    "џ": "dz", "ш": "s",
}

_LAT_DIACRITIC = {
    "č": "c", "ć": "c", "đ": "dj", "š": "s", "ž": "z",
}


def normkey(value):
    """Fold to a script-, case- and diacritic-insensitive comparison key."""
    if not value:
        return ""
    s = unicodedata.normalize("NFC", str(value)).strip().lower()
    buf = []
    for ch in s:
        if ch in _CYR_TO_LAT:
            buf.append(_CYR_TO_LAT[ch])
        elif ch in _LAT_DIACRITIC:
            buf.append(_LAT_DIACRITIC[ch])
        else:
            buf.append(ch)
    s = "".join(buf)
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def norm_parcel(value):
    """Canonical parcel form: 'main/sub', sub dropped when 0 or absent."""
    if not value:
        return ""
    m = re.match(r"^\s*(\d+)\s*(?:/\s*(\d+))?\s*$", str(value))
    if not m:
        return ""
    main, sub = m.group(1).lstrip("0") or "0", m.group(2)
    if sub is None:
        return main
    sub = sub.lstrip("0") or "0"
    return main if sub == "0" else f"{main}/{sub}"


def norm_admin(value):
    """Normalize a municipality name and drop common administrative prefixes."""
    key = normkey(value)
    key = re.sub(r"^(?:gradska\s+)?opstina\s+", "", key)
    key = re.sub(r"^grad\s+", "", key)
    key = re.sub(r"\s+grad$", "", key)
    return key.strip()


def norm_house_number(value):
    """Normalize script/case/spacing while preserving number separators."""
    if not value:
        return ""
    s = unicodedata.normalize("NFC", str(value)).strip().lower()
    buf = []
    for ch in s:
        if ch in _CYR_TO_LAT:
            buf.append(_CYR_TO_LAT[ch])
        elif ch in _LAT_DIACRITIC:
            buf.append(_LAT_DIACRITIC[ch])
        else:
            buf.append(ch)
    return re.sub(r"[^a-z0-9/-]+", "", "".join(buf))
