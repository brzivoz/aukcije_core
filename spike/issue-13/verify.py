#!/usr/bin/env python3
"""Verify issue #13 decision fixtures without accessing the network."""

import json
import re
from pathlib import Path

from pyproj import Transformer


ROOT = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures"
SENSITIVE_KEY = re.compile(
    r"(^|_)(authorization|cookie|password|secret|session_id|token|jmbg|owner)(_|$)",
    re.IGNORECASE,
)
SENSITIVE_VALUE = re.compile(
    r"\bBearer\s+\S+|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}",
    re.IGNORECASE,
)


def read_json(name):
    with (FIXTURES / name).open(encoding="utf-8") as handle:
        return json.load(handle)


def scan_redaction(value, path="$"):
    if isinstance(value, dict):
        for key, child in value.items():
            assert not SENSITIVE_KEY.search(key), f"sensitive key at {path}.{key}"
            scan_redaction(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            scan_redaction(child, f"{path}[{index}]")
    elif isinstance(value, str):
        assert not SENSITIVE_VALUE.search(value), f"sensitive value at {path}"


def verify_contract(contract):
    assert contract["decision"] == "C"
    assert contract["capability"] == "PARCEL_GEOMETRY_UNAVAILABLE"
    assert contract["reason"] == "UNCONFIRMED_REUSE_AUTHORITY"
    assert contract["production_rgz_request_count"] == 0
    assert contract["fallback_issue"] == 23
    assert contract["precision_rules"]["verified_parcel_geometry"] == "PARCEL"
    assert contract["precision_rules"]["address_registry_point"] == "ADDRESS"
    assert contract["precision_rules"]["ko_centroid"] == "KO"


def verify_guest_observation(observation):
    assert observation["authentication"] == "none"
    assert observation["login_control_visible"] is True
    request = observation["wms_observation"]["request"]
    assert request["method"] == "GET"
    assert request["path"] == "/rga-portal-api/gis/wms/proxy"
    assert request["query"]["SERVICE"] == "WMS"
    assert request["query"]["REQUEST"] == "GetMap"
    assert request["query"]["VERSION"] == "1.3.0"
    assert request["query"]["CRS"] == "EPSG:32634"
    assert request["query"]["LAYERS"] == "dkp:dkp_parcels_weekly_only_utm"
    assert request["query"]["BBOX"] == "[REDACTED_VIEWPORT]"
    response = observation["wms_observation"]["response"]
    assert response["media_type"] == "image/png"
    assert response["body_committed"] is False


def verify_capabilities(capabilities):
    request = capabilities["request"]
    response = capabilities["response"]
    layer = capabilities["selected_layer"]
    assert request["authentication"] == "none"
    assert request["query"] == {
        "service": "WMS",
        "request": "GetCapabilities",
        "version": "1.3.0",
    }
    assert response["status"] == 200
    assert response["media_type"] == "text/xml"
    assert response["raw_body_committed"] is False
    assert re.fullmatch(r"[0-9a-f]{64}", response["raw_sha256"])
    assert response["raw_size_bytes"] == 22038
    assert capabilities["service"]["version"] == "1.3.0"
    assert capabilities["service"]["update_sequence"] == "6441"
    assert layer["name"] == "dkp:dkp_parcels_weekly_only_utm"
    assert layer["queryable"] is True
    assert set(layer["crs"]) == {"EPSG:25834", "CRS:84"}
    assert layer["max_scale_denominator"] == 15000.0
    assert capabilities["license_interpretation"] == "NOT_A_REUSE_LICENSE"


def verify_crs(samples):
    forward = {
        "EPSG:25834": Transformer.from_crs("EPSG:4326", "EPSG:25834", always_xy=True),
        "EPSG:32634": Transformer.from_crs("EPSG:4326", "EPSG:32634", always_xy=True),
    }
    reverse = {
        crs: Transformer.from_crs(crs, "EPSG:4326", always_xy=True)
        for crs in forward
    }
    checks = 0
    for sample in samples:
        lon = sample["wgs84"]["longitude"]
        lat = sample["wgs84"]["latitude"]
        calculated = {}
        for crs in ("EPSG:25834", "EPSG:32634"):
            x, y = forward[crs].transform(lon, lat)
            expected = sample["expected"][crs]
            assert abs(x - expected["easting"]) <= 0.01
            assert abs(y - expected["northing"]) <= 0.01
            lon2, lat2 = reverse[crs].transform(x, y)
            assert max(abs(lon2 - lon), abs(lat2 - lat)) <= 1e-9
            calculated[crs] = (x, y)
            checks += 1
        dx = calculated["EPSG:25834"][0] - calculated["EPSG:32634"][0]
        dy = calculated["EPSG:25834"][1] - calculated["EPSG:32634"][1]
        assert (dx * dx + dy * dy) ** 0.5 <= 0.01
    return checks


def main():
    observation = read_json("guest-map-observation.json")
    capabilities = read_json("capabilities-parcel-layer.json")
    samples = read_json("ko-parcel-samples.json")
    contract = read_json("unavailable-contract.json")
    for fixture in (observation, capabilities, samples, contract):
        scan_redaction(fixture)
    verify_contract(contract)
    verify_guest_observation(observation)
    verify_capabilities(capabilities)
    crs_checks = verify_crs(samples)
    print(
        "issue #13 evidence OK: decision C; "
        f"4 redacted fixtures; {len(samples)} samples; {crs_checks} CRS transforms"
    )


if __name__ == "__main__":
    main()
