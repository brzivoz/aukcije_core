#!/usr/bin/env python3
"""Fetch one parcel from the public RGZ WFS for occasional private use.

The command performs one unauthenticated, exact KO + parcel request, requests
at most two features so ambiguity is detectable, validates the response, and
writes a property-whitelisted GeoJSON file under the gitignored ``out``
directory. It is intentionally not a batch importer or application adapter.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import math
import os
import re
import sys
import tempfile
import unicodedata
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
ENDPOINT = "https://ogc-tmp.geosrbija.rs/regdkp/ows"
FEATURE_TYPE = "dkp:dkp_parcels_weekly_only_utm"
OUTPUT_CRS = "EPSG:4326"
MAX_FEATURES = 2
USER_AGENT = "aukcije-core-issue-13/private-parcel-lookup"
PARCEL_RE = re.compile(r"[0-9]+(?:/[0-9]+)?\Z")
SERBIA_BOUNDS = (18.0, 41.0, 24.0, 47.0)
SAFE_PROPERTIES = {
    "id",
    "source_is",
    "source_parcel_id",
    "mun_code",
    "mun_name_cyr",
    "mun_name_lat",
    "city_code",
    "city_name_cyr",
    "city_name_lat",
    "cadmun_code",
    "cadmun_name_cyr",
    "cadmun_name_lat",
    "parcel_num",
    "parcel_status_code",
    "parcel_status_name_cyr",
    "parcel_status_name_lat",
    "area",
    "dkp_status_code",
    "dkp_status_name_cyr",
    "dkp_status_name_lat",
    "uipn",
    "source_projection",
    "scale",
}


class LookupFailure(Exception):
    """A safe, user-facing lookup failure with a stable exit code."""

    def __init__(self, message: str, exit_code: int) -> None:
        super().__init__(message)
        self.exit_code = exit_code


def normalize_ko(value: str) -> str:
    normalized = " ".join(unicodedata.normalize("NFC", value).upper().strip().split())
    if not (2 <= len(normalized) <= 80):
        raise LookupFailure("KO must contain 2-80 Latin letters, digits, spaces, dots, or hyphens", 2)
    if not normalized[0].isalnum() or any(
        not (
            (char.isalpha() and "LATIN" in unicodedata.name(char, ""))
            or char in "0123456789"
            or char in " .-"
        )
        for char in normalized
    ):
        raise LookupFailure("KO must contain 2-80 Latin letters, digits, spaces, dots, or hyphens", 2)
    return normalized


def output_slug(ko: str) -> str:
    transliterated = ko.translate(str.maketrans({"Đ": "DJ", "đ": "dj"}))
    decomposed = unicodedata.normalize("NFKD", transliterated)
    ascii_name = "".join(char for char in decomposed if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", "-", ascii_name.lower()).strip("-") or "ko"


def output_filename(ko: str, parcel: str) -> str:
    readable = f"{output_slug(ko)}-{parcel.replace('/', '-')}"
    identity = hashlib.sha256(f"{ko}\0{parcel}".encode()).hexdigest()[:16]
    return f"{readable}-{identity}.geojson"


def normalize_parcel(value: str) -> str:
    normalized = value.strip()
    if len(normalized) > 32 or not PARCEL_RE.fullmatch(normalized):
        raise LookupFailure("parcel must be at most 32 digits, optionally with one / separator", 2)
    return normalized


def build_url(ko: str, parcel: str) -> str:
    # Inputs have already been reduced to strict allowlists, so the CQL values
    # cannot inject operators or quotes.
    query = {
        "service": "WFS",
        "version": "2.0.0",
        "request": "GetFeature",
        "typeNames": FEATURE_TYPE,
        "outputFormat": "application/json",
        "srsName": OUTPUT_CRS,
        "count": str(MAX_FEATURES),
        "cql_filter": f"cadmun_name_lat='{ko}' AND parcel_num='{parcel}'",
    }
    return f"{ENDPOINT}?{urlencode(query)}"


def validate_position(value: Any) -> tuple[float, float]:
    if not isinstance(value, list) or len(value) != 2:
        raise LookupFailure("RGZ geometry contains an invalid 2D position", 5)
    if any(not isinstance(item, (int, float)) or isinstance(item, bool) for item in value):
        raise LookupFailure("RGZ geometry contains a non-numeric position", 5)
    lon, lat = float(value[0]), float(value[1])
    if not math.isfinite(lon) or not math.isfinite(lat):
        raise LookupFailure("RGZ returned a non-finite coordinate", 5)
    min_lon, min_lat, max_lon, max_lat = SERBIA_BOUNDS
    if not (min_lon <= lon <= max_lon and min_lat <= lat <= max_lat):
        raise LookupFailure("RGZ geometry is outside the expected Serbia bounds", 5)
    return lon, lat


def validate_ring(value: Any) -> None:
    if not isinstance(value, list) or len(value) < 4:
        raise LookupFailure("RGZ geometry ring must contain at least four positions", 5)
    positions = [validate_position(position) for position in value]
    if positions[0] != positions[-1]:
        raise LookupFailure("RGZ geometry ring is not closed", 5)
    if len(set(positions[:-1])) < 3:
        raise LookupFailure("RGZ geometry ring has fewer than three distinct vertices", 5)
    signed_double_area = sum(
        first[0] * second[1] - second[0] * first[1]
        for first, second in zip(positions, positions[1:])
    )
    if abs(signed_double_area) <= 1e-15:
        raise LookupFailure("RGZ geometry ring has zero area", 5)


def validate_polygon_coordinates(value: Any) -> None:
    if not isinstance(value, list) or not value:
        raise LookupFailure("RGZ polygon must contain at least one linear ring", 5)
    for ring in value:
        validate_ring(ring)


def validate_geometry(geometry: Any) -> None:
    if not isinstance(geometry, dict):
        raise LookupFailure("RGZ returned an unsupported or missing geometry", 5)
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if geometry_type == "Polygon":
        validate_polygon_coordinates(coordinates)
        return
    if geometry_type == "MultiPolygon":
        if not isinstance(coordinates, list) or not coordinates:
            raise LookupFailure("RGZ multipolygon must contain at least one polygon", 5)
        for polygon in coordinates:
            validate_polygon_coordinates(polygon)
        return
    raise LookupFailure("RGZ returned an unsupported or missing geometry", 5)


def sanitize_response(raw: bytes, requested_ko: str, requested_parcel: str) -> dict[str, Any]:
    try:
        payload = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise LookupFailure("RGZ did not return valid JSON", 5) from exc

    if not isinstance(payload, dict) or payload.get("type") != "FeatureCollection":
        raise LookupFailure("RGZ did not return a GeoJSON FeatureCollection", 5)
    features = payload.get("features")
    if not isinstance(features, list):
        raise LookupFailure("RGZ response has no feature list", 5)
    if not features:
        raise LookupFailure(f"parcel not found: KO {requested_ko}, parcel {requested_parcel}", 3)
    if len(features) > 1:
        raise LookupFailure(
            f"ambiguous parcel: KO {requested_ko}, parcel {requested_parcel} matched at least two features",
            4,
        )

    feature = features[0]
    if not isinstance(feature, dict) or feature.get("type") != "Feature":
        raise LookupFailure("RGZ returned an invalid GeoJSON feature", 5)
    properties = feature.get("properties")
    if not isinstance(properties, dict):
        raise LookupFailure("RGZ feature has no properties", 5)
    if properties.get("cadmun_name_lat") != requested_ko or str(properties.get("parcel_num")) != requested_parcel:
        raise LookupFailure("RGZ feature identity does not match the requested KO and parcel", 5)
    if not isinstance(properties.get("cadmun_code"), int):
        raise LookupFailure("RGZ feature has no numeric cadastral-municipality code", 5)
    area = properties.get("area")
    if (
        not isinstance(area, (int, float))
        or isinstance(area, bool)
        or not math.isfinite(float(area))
        or area <= 0
    ):
        raise LookupFailure("RGZ feature has no positive area", 5)

    validate_geometry(feature.get("geometry"))
    response_crs = payload.get("crs")
    crs_name = response_crs.get("properties", {}).get("name") if isinstance(response_crs, dict) else None
    if crs_name not in {"urn:ogc:def:crs:EPSG::4326", OUTPUT_CRS}:
        raise LookupFailure(f"RGZ returned unexpected CRS: {crs_name!r}", 5)

    safe_properties = {key: properties[key] for key in SAFE_PROPERTIES if key in properties}
    return {
        "type": "FeatureCollection",
        "metadata": {
            "source": ENDPOINT,
            "service": "WFS 2.0.0",
            "feature_type": FEATURE_TYPE,
            "requested_ko": requested_ko,
            "requested_parcel": requested_parcel,
            "crs": OUTPUT_CRS,
            "retrieved_at": payload.get("timeStamp"),
            "raw_response_sha256": hashlib.sha256(raw).hexdigest(),
            "usage_scope": "occasional private non-commercial lookup",
            "redistribution": "not permitted by this project decision",
        },
        "features": [
            {
                "type": "Feature",
                "id": feature.get("id"),
                "properties": safe_properties,
                "geometry": feature["geometry"],
            }
        ],
    }


def fetch(url: str, timeout: float) -> bytes:
    request = Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    try:
        with urlopen(request, timeout=timeout) as response:
            content_type = response.headers.get_content_type()
            raw = response.read(5_000_001)
    except HTTPError as exc:
        raise LookupFailure(f"RGZ returned HTTP {exc.code}", 5) from exc
    except (OSError, http.client.HTTPException) as exc:
        detail = exc.reason if isinstance(exc, URLError) else exc
        raise LookupFailure(f"RGZ request failed: {detail}", 5) from exc
    if len(raw) > 5_000_000:
        raise LookupFailure("RGZ response exceeded the 5 MB safety limit", 5)
    if content_type not in {"application/json", "application/geo+json"}:
        raise LookupFailure(f"RGZ returned unexpected content type: {content_type}", 5)
    return raw


def write_private_output(payload: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temporary, output)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ko", required=True, help="cadastral municipality, e.g. DIMITROVGRAD")
    parser.add_argument("--parcel", required=True, help="parcel number, e.g. 1572 or 4577/337")
    parser.add_argument("--timeout", type=float, default=20.0, help="request timeout in seconds (default: 20)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        ko = normalize_ko(args.ko)
        parcel = normalize_parcel(args.parcel)
        if not (0 < args.timeout <= 60):
            raise LookupFailure("timeout must be greater than 0 and at most 60 seconds", 2)
        output = ROOT / "out" / output_filename(ko, parcel)
        raw = fetch(build_url(ko, parcel), args.timeout)
        payload = sanitize_response(raw, ko, parcel)
        write_private_output(payload, output)
    except LookupFailure as exc:
        print(f"lookup failed: {exc}", file=sys.stderr)
        return exc.exit_code
    except OSError as exc:
        print(f"lookup failed: could not write private output: {exc}", file=sys.stderr)
        return 5

    feature = payload["features"][0]
    properties = feature["properties"]
    print(
        f"saved 1 {feature['geometry']['type']} for KO {properties['cadmun_name_lat']} "
        f"({properties['cadmun_code']}), parcel {properties['parcel_num']}, "
        f"area {properties['area']} m2 to {output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
