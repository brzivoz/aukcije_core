#!/usr/bin/env python3
"""Verify issue #13 fixtures and lookup behavior without network access."""

import contextlib
import http.client
import importlib.util
import io
import json
import re
import ssl
from pathlib import Path
from types import SimpleNamespace

from pyproj import Transformer


ROOT = Path(__file__).resolve().parent
REPOSITORY = ROOT.parent.parent
FIXTURES = ROOT / "fixtures"
SENSITIVE_KEY = re.compile(
    r"(^|_)(authorization|cookie|password|secret|session_id|token|jmbg|owner)(_|$)",
    re.IGNORECASE,
)
SENSITIVE_VALUE = re.compile(
    r"\bBearer\s+\S+|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}",
    re.IGNORECASE,
)


def check(condition, message="verification check failed"):
    if not condition:
        raise AssertionError(message)


def load_lookup_module():
    spec = importlib.util.spec_from_file_location("issue13_fetch_parcel", ROOT / "fetch_parcel.py")
    check(spec and spec.loader, "could not load fetch_parcel.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_json(name):
    with (FIXTURES / name).open(encoding="utf-8") as handle:
        return json.load(handle)


def scan_redaction(value, path="$"):
    if isinstance(value, dict):
        for key, child in value.items():
            check(not SENSITIVE_KEY.search(key), f"sensitive key at {path}.{key}")
            scan_redaction(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            scan_redaction(child, f"{path}[{index}]")
    elif isinstance(value, str):
        check(not SENSITIVE_VALUE.search(value), f"sensitive value at {path}")


def verify_contract(contract):
    check(contract["decision"] == "B")
    check(contract["capability"] == "PARCEL_GEOMETRY_PRIVATE_ON_DEMAND")
    check(contract["usage_scope"] == "OCCASIONAL_PRIVATE_NON_COMMERCIAL")
    check(contract["service"]["type"] == "OGC_WFS")
    check(contract["service"]["version"] == "2.0.0")
    check(contract["service"]["authentication"] == "none")
    check(contract["service"]["request_crs"] == "EPSG:4326")
    check(contract["service"]["advertised_default_crs"] == "EPSG:25834")
    request = contract["request_contract"]
    check(request["lookup_key"] == ["cadmun_name_lat", "parcel_num"])
    check(request["requests_per_invocation"] == 1)
    check(request["maximum_returned_features"] == 2)
    check(request["automatic_retry_count"] == 0)
    check(request["timeout_seconds_minimum_exclusive"] == 0)
    check(request["timeout_seconds_maximum"] == 60)
    boundaries = contract["boundaries"]
    check(boundaries["manual_invocation_only"] is True)
    for forbidden in (
        "background_or_scheduled_access",
        "bulk_access",
        "authenticated_access",
        "browser_session_reuse",
        "personal_data",
        "shared_or_product_cache",
        "redistribution",
    ):
        check(boundaries[forbidden] is False)
    check(boundaries["private_local_output_is_gitignored"] is True)
    check(contract["fallback_issue"] == 23)
    check(contract["precision_rules"]["validated_exact_wfs_geometry"] == "PARCEL")
    check(contract["precision_rules"]["address_registry_point"] == "ADDRESS")


def verify_guest_observation(observation):
    check(observation["authentication"] == "none")
    check(observation["login_control_visible"] is True)
    request = observation["wms_observation"]["request"]
    check(request["method"] == "GET")
    check(request["path"] == "/rga-portal-api/gis/wms/proxy")
    check(request["query"]["SERVICE"] == "WMS")
    check(request["query"]["REQUEST"] == "GetMap")
    check(request["query"]["VERSION"] == "1.3.0")
    check(request["query"]["CRS"] == "EPSG:32634")
    check(request["query"]["LAYERS"] == "dkp:dkp_parcels_weekly_only_utm")
    check(request["query"]["BBOX"] == "[REDACTED_VIEWPORT]")
    response = observation["wms_observation"]["response"]
    check(response["media_type"] == "image/png")
    check(response["body_committed"] is False)


def verify_wms_capabilities(capabilities):
    request = capabilities["request"]
    response = capabilities["response"]
    layer = capabilities["selected_layer"]
    check(request["authentication"] == "none")
    check(
        request["query"]
        == {
            "service": "WMS",
            "request": "GetCapabilities",
            "version": "1.3.0",
        }
    )
    check(response["status"] == 200)
    check(response["media_type"] == "text/xml")
    check(response["raw_body_committed"] is False)
    check(re.fullmatch(r"[0-9a-f]{64}", response["raw_sha256"]))
    check(response["raw_size_bytes"] == 22038)
    check(layer["name"] == "dkp:dkp_parcels_weekly_only_utm")
    check(layer["queryable"] is True)
    check(set(layer["crs"]) == {"EPSG:25834", "CRS:84"})


def verify_wfs_observations(observations, samples):
    check(observations["authentication"] == "none")
    capabilities = observations["capabilities"]
    check(capabilities["status"] == 200)
    check(capabilities["raw_size_bytes"] == 96493)
    check(capabilities["fees"] == "")
    check(capabilities["access_constraints"] == "")
    check(capabilities["default_crs"] == "EPSG:25834")
    check(capabilities["json_output_advertised"] is True)
    schema = observations["schema"]
    check(schema["status"] == 200)
    check(schema["identity_fields"] == ["cadmun_code", "cadmun_name_lat", "parcel_num"])
    check(schema["geometry_field"] == "geom")
    for raw_hash in (capabilities["raw_sha256"], schema["raw_sha256"]):
        check(re.fullmatch(r"[0-9a-f]{64}", raw_hash))

    success = observations["success"]
    check(len(success) == 3)
    check(
        {(item["ko"], item["parcel"]) for item in success}
        == {(sample.get("wfs_ko_exact", sample["ko"]), sample["parcel"]) for sample in samples}
    )
    for item in success:
        check(item["number_matched"] == 1)
        check(item["geometry_type"] in {"Polygon", "MultiPolygon"})
        check(item["area_square_metres"] > 0)
        check(isinstance(item["cadmun_code"], int))
        check(re.fullmatch(r"[0-9a-f]{64}", item["raw_sha256"]))

    check(observations["not_found"]["number_matched"] == 0)
    check(observations["not_found"]["number_returned"] == 0)
    check(observations["ambiguous"]["number_matched"] == 37)
    check(observations["ambiguous"]["number_returned_with_count_2"] == 2)
    check("exit 2" in observations["error"]["client_behavior"])
    check("exit 5" in observations["error"]["transport_or_schema_behavior"])


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
            check(abs(x - expected["easting"]) <= 0.01)
            check(abs(y - expected["northing"]) <= 0.01)
            lon2, lat2 = reverse[crs].transform(x, y)
            check(max(abs(lon2 - lon), abs(lat2 - lat)) <= 1e-9)
            calculated[crs] = (x, y)
            checks += 1
        dx = calculated["EPSG:25834"][0] - calculated["EPSG:32634"][0]
        dy = calculated["EPSG:25834"][1] - calculated["EPSG:32634"][1]
        check((dx * dx + dy * dy) ** 0.5 <= 0.01)
    return checks


def mock_response(feature_count=1, ko="ČAJETINA", parcel="4577/337"):
    feature = {
        "type": "Feature",
        "id": "fixture.1",
        "properties": {
            "cadmun_code": 743968,
            "cadmun_name_lat": ko,
            "parcel_num": parcel,
            "area": 410,
            "source_projection": "GK7",
            "owner": "must be dropped",
        },
        "geometry": {
            "type": "Polygon",
            "coordinates": [[[19.69, 43.73], [19.70, 43.73], [19.70, 43.74], [19.69, 43.73]]],
        },
    }
    return json.dumps(
        {
            "type": "FeatureCollection",
            "timeStamp": "2026-08-21T00:00:00Z",
            "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:EPSG::4326"}},
            "features": [feature for _ in range(feature_count)],
        }
    ).encode()


def verify_lookup_client(module):
    check(module.normalize_ko(" čajetina ") == "ČAJETINA")
    check(module.normalize_ko("voždovac") == "VOŽDOVAC")
    check(module.normalize_parcel("4577/337") == "4577/337")
    check(module.output_slug("ČAJETINA") == "cajetina")
    check(
        module.output_filename("CAJETINA-4577", "337")
        != module.output_filename("CAJETINA", "4577/337"),
        "distinct KO + parcel identities must not share an output filename",
    )
    url = module.build_url("ČAJETINA", "4577/337")
    check(url.startswith(module.ENDPOINT + "?"))
    check("count=2" in url and "CQL" not in url)

    sanitized = module.sanitize_response(mock_response(), "ČAJETINA", "4577/337")
    check(len(sanitized["features"]) == 1)
    check(sanitized["features"][0]["geometry"]["type"] == "Polygon")
    check("owner" not in sanitized["features"][0]["properties"])
    check(sanitized["metadata"]["usage_scope"] == "occasional private non-commercial lookup")

    valid_multipolygon = json.loads(mock_response())
    valid_multipolygon["features"][0]["geometry"]["type"] = "MultiPolygon"
    valid_multipolygon["features"][0]["geometry"]["coordinates"] = [
        valid_multipolygon["features"][0]["geometry"]["coordinates"]
    ]
    module.sanitize_response(json.dumps(valid_multipolygon).encode(), "ČAJETINA", "4577/337")

    wrong_crs = json.loads(mock_response())
    wrong_crs["crs"]["properties"]["name"] = "urn:ogc:def:crs:EPSG::25834"
    wrong_geometry = json.loads(mock_response())
    wrong_geometry["features"][0]["geometry"] = {"type": "Point", "coordinates": [19.69, 43.73]}
    shallow_polygon = json.loads(mock_response())
    shallow_polygon["features"][0]["geometry"]["coordinates"] = shallow_polygon["features"][0][
        "geometry"
    ]["coordinates"][0]
    identical_points = json.loads(mock_response())
    identical_points["features"][0]["geometry"]["coordinates"] = [
        [[19.69, 43.73], [19.69, 43.73], [19.69, 43.73], [19.69, 43.73]]
    ]
    unclosed_ring = json.loads(mock_response())
    unclosed_ring["features"][0]["geometry"]["coordinates"] = [
        [[19.69, 43.73], [19.70, 43.73], [19.70, 43.74], [19.69, 43.74]]
    ]
    null_position = json.loads(mock_response())
    null_position["features"][0]["geometry"]["coordinates"] = [
        [[19.69, 43.73], [19.70, 43.73], None, [19.69, 43.73]]
    ]
    zero_area_ring = json.loads(mock_response())
    zero_area_ring["features"][0]["geometry"]["coordinates"] = [
        [[19.69, 43.73], [19.70, 43.73], [19.71, 43.73], [19.69, 43.73]]
    ]
    non_finite_area = json.loads(mock_response())
    non_finite_area["features"][0]["properties"]["area"] = float("nan")
    deeply_nested_json = b"[" * 2000 + b"0" + b"]" * 2000
    cases = [
        (lambda: module.normalize_parcel("1' OR '1'='1"), 2),
        (lambda: module.normalize_ko("ВОЖДОВАЦ"), 2),
        (lambda: module.sanitize_response(mock_response(0), "ČAJETINA", "4577/337"), 3),
        (lambda: module.sanitize_response(mock_response(2), "ČAJETINA", "4577/337"), 4),
        (lambda: module.sanitize_response(mock_response(1, ko="OTHER"), "ČAJETINA", "4577/337"), 5),
        (lambda: module.sanitize_response(b"not json", "ČAJETINA", "4577/337"), 5),
        (lambda: module.sanitize_response(deeply_nested_json, "ČAJETINA", "4577/337"), 5),
        (
            lambda: module.sanitize_response(json.dumps(wrong_crs).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
        (
            lambda: module.sanitize_response(json.dumps(wrong_geometry).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
        (
            lambda: module.sanitize_response(json.dumps(shallow_polygon).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
        (
            lambda: module.sanitize_response(json.dumps(identical_points).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
        (
            lambda: module.sanitize_response(json.dumps(unclosed_ring).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
        (
            lambda: module.sanitize_response(json.dumps(null_position).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
        (
            lambda: module.sanitize_response(json.dumps(zero_area_ring).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
        (
            lambda: module.sanitize_response(json.dumps(non_finite_area).encode(), "ČAJETINA", "4577/337"),
            5,
        ),
    ]
    for action, expected_exit in cases:
        try:
            action()
        except module.LookupFailure as exc:
            check(exc.exit_code == expected_exit)
        else:
            raise AssertionError(f"expected LookupFailure exit {expected_exit}")

    class FakeHeaders:
        @staticmethod
        def get_content_type():
            return "application/json"

    class OversizedResponse:
        headers = FakeHeaders()

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        @staticmethod
        def read(_limit):
            return b"x" * 5_000_001

    original_urlopen = module.urlopen
    try:
        module.urlopen = lambda *_args, **_kwargs: OversizedResponse()
        try:
            module.fetch(module.build_url("ČAJETINA", "4577/337"), 1)
        except module.LookupFailure as exc:
            check(exc.exit_code == 5)
            check("5 MB" in str(exc))
        else:
            raise AssertionError("expected oversized response failure")
    finally:
        module.urlopen = original_urlopen

    class ReadFailureResponse:
        headers = FakeHeaders()

        def __init__(self, error):
            self.error = error

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, _limit):
            raise self.error

    read_errors = (
        ConnectionResetError("connection reset during read"),
        http.client.IncompleteRead(b"partial", 100),
        ssl.SSLError("TLS read failed"),
    )
    try:
        for error in read_errors:
            module.urlopen = lambda *_args, _error=error, **_kwargs: ReadFailureResponse(_error)
            try:
                module.fetch(module.build_url("ČAJETINA", "4577/337"), 1)
            except module.LookupFailure as exc:
                check(exc.exit_code == 5, f"{type(error).__name__} did not map to exit 5")
            else:
                raise AssertionError(f"expected {type(error).__name__} to fail closed")
    finally:
        module.urlopen = original_urlopen

    original_parse_args = module.parse_args
    original_fetch = module.fetch
    original_write = module.write_private_output
    writes = []
    try:
        module.parse_args = lambda: SimpleNamespace(ko="ČAJETINA", parcel="4577/337", timeout=1.0)
        module.fetch = lambda *_args, **_kwargs: b"not json"
        module.write_private_output = lambda *_args, **_kwargs: writes.append(True)
        with contextlib.redirect_stderr(io.StringIO()):
            check(module.main() == 5)
        check(writes == [])

        module.fetch = lambda *_args, **_kwargs: mock_response()

        def fail_write(*_args, **_kwargs):
            raise PermissionError("private output denied")

        module.write_private_output = fail_write
        with contextlib.redirect_stderr(io.StringIO()):
            check(module.main() == 5)
    finally:
        module.parse_args = original_parse_args
        module.fetch = original_fetch
        module.write_private_output = original_write

    ignore = (REPOSITORY / ".gitignore").read_text(encoding="utf-8")
    check("spike/issue-13/out/" in ignore)


def main():
    observation = read_json("guest-map-observation.json")
    wms_capabilities = read_json("capabilities-parcel-layer.json")
    wfs_observations = read_json("wfs-observations.json")
    samples = read_json("ko-parcel-samples.json")
    contract = read_json("private-wfs-contract.json")
    for fixture in (observation, wms_capabilities, wfs_observations, samples, contract):
        scan_redaction(fixture)
    verify_contract(contract)
    verify_guest_observation(observation)
    verify_wms_capabilities(wms_capabilities)
    verify_wfs_observations(wfs_observations, samples)
    crs_checks = verify_crs(samples)
    verify_lookup_client(load_lookup_module())
    print(
        "issue #13 evidence OK: decision B for private on-demand use; "
        f"3 live WFS parcel matches; {crs_checks} CRS transforms; client failure cases OK"
    )


if __name__ == "__main__":
    main()
