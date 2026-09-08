#!/usr/bin/env python3
"""Verify issue #41's sanitized automatic-access contract offline."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


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
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def check(condition: bool, message: str = "verification check failed") -> None:
    if not condition:
        raise AssertionError(message)


def read_json(name: str) -> Any:
    with (FIXTURES / name).open(encoding="utf-8") as handle:
        return json.load(handle)


def scan_redaction(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            check(not SENSITIVE_KEY.search(key), f"sensitive key at {path}.{key}")
            scan_redaction(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            scan_redaction(child, f"{path}[{index}]")
    elif isinstance(value, str):
        check(not SENSITIVE_VALUE.search(value), f"sensitive value at {path}")


def verify_sources(review: dict[str, Any]) -> None:
    check(review["decision_date"] == "2026-09-03")
    sources = review["sources"]
    check(len(sources) == 8)
    check(all(item["url"].startswith("https://") for item in sources))
    by_id = {item["id"]: item for item in sources}
    check(len(by_id) == len(sources), "source ids must be unique")
    for source_id in ("rgz-electronic-service-terms", "rgz-geosrbija-overview"):
        check(by_id[source_id]["authorizes_automatic_wfs"] is False)
        check(by_id[source_id]["authorizes_redistribution"] is False)
    tariff = by_id["administrative-fee-law-tariff-215i"]
    check(tariff["amount_source"] == "Official Gazette RS 55/2025")
    check(any("228010 RSD" in finding for finding in tariff["findings"]))
    check(tariff["authorizes_anonymous_automatic_wfs"] is False)
    capabilities = by_id["regdkp-wfs-capabilities"]
    check(capabilities["outcome"] == "CONNECT_TIMEOUT")
    check(capabilities["read_date"] == "2026-09-03")
    check(capabilities["checked_at_utc"] == "2026-09-03T14:35:26Z")
    check(capabilities["authentication"] == "none")
    check(capabilities["credentials_or_cookies_used"] is False)
    for schema_id in ("regdkp-parcel-schema", "regdkp-object-schema"):
        check(by_id[schema_id]["outcome"] == "CONNECT_TIMEOUT")
        check(by_id[schema_id]["read_date"] == "2026-09-03")
        check(by_id[schema_id]["checked_at_utc"] == "2026-09-03T14:35:26Z")
        check(by_id[schema_id]["authentication"] == "none")
        check(by_id[schema_id]["credentials_or_cookies_used"] is False)
    check(review["rechecked_on"] == "2026-09-08")
    rechecks = review["rechecks"]
    check({item["id"] for item in rechecks} == {
        "regdkp-wfs-capabilities", "regdkp-parcel-schema", "regdkp-object-schema"
    })
    check(len(rechecks) == 3)
    for source in rechecks:
        check(source["url"] == by_id[source["id"]]["url"])
        check(source["read_date"] == "2026-09-08")
        check(source["outcome"] == "HTTP_200")
        check(source["authentication"] == "none")
        check(source["credentials_or_cookies_used"] is False)
        check(SHA256.fullmatch(source["raw_sha256"]) is not None)
    check(review["contact"]["contacted_for_issue_41"] is False)
    check("owner directed implementation" in review["contact"]["reason"])


def verify_wfs(evidence: dict[str, Any]) -> None:
    historical = evidence["historical_recheck_2026_09_03"]
    check(historical["checked_at_utc"] == "2026-09-03T14:35:26Z")
    check(historical["capabilities_read"] is False)
    check(historical["schema_read_attempts"] == 2)
    check(historical["schema_reads_succeeded"] == 0)
    current = evidence["current_recheck"]
    check(current["checked_at_utc"] == "2026-09-08T06:30:05Z")
    check(current["capabilities_read"] is True)
    check(current["schema_read_attempts"] == current["schema_reads_succeeded"] == 2)
    check(current["authentication"] == "none")
    check(current["raw_bodies_committed"] is False)
    for key in ("capabilities", "parcel_schema", "object_schema", "parcel_lookup"):
        check(current[key]["status"] == 200)
        check(SHA256.fullmatch(current[key]["raw_sha256"]) is not None)
    check(current["capabilities"]["fees"] == current["capabilities"]["access_constraints"] == "")
    check(current["capabilities"]["publisher_dataset_edition"] is None)
    check(current["capabilities"]["feature_type_inventory_unchanged"] is True)
    check(current["parcel_schema"]["identity_fields"] == {
        "cadmun_code": "xsd:int", "parcel_num": "xsd:string"
    })
    check(current["parcel_schema"]["geometry_field"] == "geom")
    check(current["parcel_schema"]["raw_size_bytes"] == 3309)
    object_schema = current["object_schema"]
    check(object_schema["feature_type"] == "dkp:objekat")
    check(object_schema["raw_size_bytes"] == 3024)
    check(object_schema["candidate_identity_fields"] == {
        "objectid": "xsd:int", "maticnibrojko": "xsd:int", "brparcele": "xsd:string",
        "brdelaparc": "xsd:int", "deoparcele_id": "xsd:string"
    })
    check(object_schema["geometry_field"] == "wkb_geometry")
    check(object_schema["geometry_schema_type"] == "gml:GeometryPropertyType")
    check(object_schema["declared_default_crs"] == "EPSG:32634")
    check(object_schema["building_records_fetched"] is False)
    check(current["parcel_lookup"]["number_matched"] == current["parcel_lookup"]["number_returned"] == 1)
    check(current["parcel_lookup"]["geometry_type"] == "MultiPolygon")
    check(current["parcel_lookup"]["response_crs"] == "EPSG:4326")
    capabilities = evidence["retained_capabilities"]
    check(current["capabilities"]["raw_sha256"] == capabilities["raw_sha256"])
    check(capabilities["wfs_version"] == "2.0.0")
    check(capabilities["update_sequence"] == "6441")
    check(capabilities["raw_size_bytes"] == 96493)
    check(SHA256.fullmatch(capabilities["raw_sha256"]) is not None)
    check(capabilities["raw_body_committed"] is False)
    check(capabilities["fees"] == "")
    check(capabilities["access_constraints"] == "")
    check(capabilities["accepted_as_current_runtime_contract"] is False)
    check(capabilities["runtime_default"] is False)
    check("neither automated access nor redistribution" in capabilities["empty_metadata_interpretation"])
    feature_types = capabilities["feature_types"]
    expected = {
        "dkp:katastarska_parcela": "EPSG:32634",
        "dkp:objekat": "EPSG:32634",
        "dkp:parcelparts_only_utm": "EPSG:25834",
        "dkp:parcelparts_utm": "EPSG:25834",
        "dkp:dkp_parcelparts_weekly_only_utm": "EPSG:25834",
        "dkp:parcels_only_utm": "EPSG:25834",
        "dkp:parcels_utm": "EPSG:25834",
        "dkp:dkp_parcels_weekly_only_utm": "EPSG:25834",
        "dkp:scales_utm": "EPSG:25834",
    }
    actual = {item["name"]: item["default_crs"] for item in feature_types}
    check(actual == expected, "feature-type inventory changed")
    selected = next(
        item for item in feature_types
        if item["name"] == "dkp:dkp_parcels_weekly_only_utm"
    )
    check(selected["automatic_use"] == "EXPLICIT_ACTIVATION_REQUIRES_CURRENT_PINS")
    check(all(
        item["automatic_use"] != "EXPLICIT_ACTIVATION_REQUIRES_CURRENT_PINS"
        for item in feature_types if item is not selected
    ))
    schemas = evidence["schemas"]
    check(len(schemas) == 1)
    parcel = schemas[0]
    check(parcel["feature_type"] == "dkp:dkp_parcels_weekly_only_utm")
    check(parcel["identity_fields"] == ["cadmun_code", "cadmun_name_lat", "parcel_num"])
    check(parcel["observed_geometry_types"] == ["Polygon", "MultiPolygon"])
    check(parcel["declared_default_crs"] == "EPSG:25834")
    check(parcel["request_crs"] == "EPSG:4326")
    check(SHA256.fullmatch(parcel["raw_sha256"]) is not None)
    check(parcel["raw_body_committed"] is False)
    check(parcel["accepted_as_current_runtime_contract"] is False)
    check(current["parcel_schema"]["raw_sha256"] != parcel["raw_sha256"])
    whitelist = parcel["non_personal_property_whitelist"]
    check(len(whitelist) == len(set(whitelist)))
    check({"cadmun_code", "cadmun_name_lat", "parcel_num", "area"}.issubset(whitelist))
    building = evidence["building_object_answer"]
    check(building["dated_technical_candidate"] == "dkp:objekat")
    check(building["dated_declared_default_crs"] == "EPSG:32634")
    check(building["current_schema_hash"] == object_schema["raw_sha256"])
    check(set(building["current_identity_fields"]) == set(object_schema["candidate_identity_fields"]))
    check(building["identity_fields_are_candidates"] is True)
    check(building["geometry_field"] == object_schema["geometry_field"])
    check(building["geometry_schema_type"] == object_schema["geometry_schema_type"])
    check(building["current_geometry_type"] is None)
    check(building["ko_parcel_join_candidate"] == ["maticnibrojko", "brparcele"])
    check(building["ko_parcel_join_confirmed"] is False)
    check(building["non_personal_property_whitelist"] == [])
    check(building["lawful_automatic_feature_type"] is None)
    check(building["decision"] == "BUILDING_CONTRACT_REVIEW_PENDING")
    check(building["issue_42_disposition"] == "KEEP_OPEN_PENDING_CONTRACT_REVIEW")
    check(evidence["automatic_usable_feature_types"] == [])
    implementation = evidence["implementation"]
    check(implementation["issue"] == "#21")
    check(implementation["automatic_fetching_implemented"] is True)
    check(implementation["default_enabled"] is False)
    check(implementation["activation_requires_current_external_pins"] is True)


def verify_contract(contract: dict[str, Any]) -> None:
    check(contract["decision"] == "OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION")
    check(contract["decision_id"] == "2026-09-03-issue-41-explicit-activation-v3")
    check(contract["decision_date"] == "2026-09-03")
    check(contract["amended_on"] == "2026-09-08")
    check(contract["publisher_automation_authority_confirmed"] is False)
    current = contract["current"]
    check(current["rgz.access-mode"] == "OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION")
    check(current["rgz.enabled"] is False)
    check(current["rgz.base-url"].startswith("https://"))
    check(current["rgz.feature-type"] == "dkp:dkp_parcels_weekly_only_utm")
    check(current["rgz.dataset-version"] is None)
    check(current["rgz.capabilities-sha256"] is None)
    check(current["rgz.schema-sha256"] is None)
    check(current["rgz.requests-per-second"] == 0.2)
    check(current["rgz.max-concurrency"] == 1)
    check(current["rgz.max-logical-lookups-per-run"] == 100)
    check(current["rgz.max-attempts"] == 3)
    check(current["rgz.retry-delays"] == ["5s", "15s"])
    check(current["rgz.max-retry-after"] == "60s")
    check(current["rgz.connect-timeout"] == "5s")
    check(current["rgz.read-timeout"] == "20s")
    check(current["rgz.call-timeout"] == "25s")
    check(current["rgz.max-response-bytes"] == 5_000_000)
    check(current["rgz.kill-switch-path"] == "data/control/rgz.disabled")
    activation = contract["activation"]
    check(activation["automatic_when_activated"] is True)
    check(activation["historical_2026_08_21_pins_are_runtime_defaults"] is False)
    check(activation["missing_or_malformed_pins_fail_startup_when_enabled"] is True)
    check(len(activation["required_environment"]) == 4)
    request = contract["request_contract"]
    check(request["count"] == 2)
    check(request["identity_filter"] == ["cadmun_code", "parcel_num"])
    check(request["retryable_http_statuses"] == [429, 502, 503, 504])
    check(request["kill_switch_checked_before_every_physical_request"] is True)
    cache = contract["cache_contract"]
    check(cache["cache_first"] is True)
    check(cache["key"] == [
        "feature_type",
        "publisher_dataset_version",
        "ko_code",
        "canonical_parcel_number",
    ])
    check(cache["at_most_one_logical_lookup_per_key_and_version"] is True)
    check(cache["logical_lookup_uniqueness_scope"] == "PER_ENRICHMENT_RUN")
    check(cache["terminal_outcomes"] == [
        "RESOLVED", "NOT_FOUND", "AMBIGUOUS", "INVALID"
    ])
    check(cache["invalid_outcome_scope"] == "VALIDATED_IDENTITY_OR_GEOMETRY_REJECTION")
    check(cache["transport_error_cached"] is False)
    check(cache["protocol_error_cached"] is False)
    check(cache["same_run_lookup_claim_committed_before_network"] is True)
    check(cache["same_run_lookup_claim_survives_result_rollback"] is True)
    check(cache["network_io_inside_database_transaction"] is False)
    check(cache["non_terminal_retry_scope"] == "LATER_ENRICHMENT_RUN")
    check(cache["dataset_version_change_forces_refetch"] is True)
    check(cache["metadata_pin_change_forces_refetch"] is False)
    check(cache["resolver_version_change_forces_refetch"] is False)
    check(cache["canonical_identity_table"] == "rgz_parcel_cache_keys")
    for key in (
        "legacy_duplicate_records_preserved", "cache_reuse_preserves_original_source_pins",
        "cache_reuse_while_network_disabled", "unhandled_errors_rediscovered_after_fallback_completion",
        "retry_discovery_requires_current_eligible_ko_and_dataset",
        "retry_discovery_suspended_while_network_disabled_or_killed",
        "ceiling_deferrals_prioritized_over_recent_network_failures",
    ):
        check(cache[key] is True, f"{key} must remain true")
    fail_closed = contract["fail_closed"]
    for key in (
        "credentials_or_cookies_prohibited",
        "browser_session_reuse_prohibited",
        "personal_records_prohibited",
        "unrecognized_properties_prohibited",
        "redistribution_prohibited",
        "building_fetch_prohibited",
        "preserve_immutable_evidence_on_ko_change",
        "invalidate_stale_current_selection_on_ko_change",
        "continue_to_issue_23_fallback",
    ):
        check(fail_closed[key] is True, f"{key} must remain true")
    deferred = contract["deferred"]
    check(deferred["publisher_billing_or_service_agreement"] == "deferred by operator")
    check(deferred["operator_monitoring_and_alerting"] == "deferred to issue #30")


def verify_documentation() -> None:
    decision_path = REPOSITORY / "documentation" / "2026-09-03-decision-41-rgz-automatic-geometry-access.md"
    previous_path = REPOSITORY / "documentation" / "2026-08-21-decision-13-rgz-parcel-access.md"
    roadmap_path = REPOSITORY / "documentation" / "IMPLEMENTATION_ROADMAP.md"
    readme_path = REPOSITORY / "README.md"
    properties_path = REPOSITORY / "src" / "main" / "resources" / "application.properties"
    migration_path = REPOSITORY / "src" / "main" / "resources" / "db" / "migration" / "V19__automatic_rgz_parcel_resolution.sql"
    claim_migration_path = REPOSITORY / "src" / "main" / "resources" / "db" / "migration" / "V20__durable_rgz_lookup_claims.sql"
    client_path = REPOSITORY / "src" / "main" / "java" / "rs" / "sud" / "eaukcija" / "rgz" / "RgzParcelClient.java"
    service_path = REPOSITORY / "src" / "main" / "java" / "rs" / "sud" / "eaukcija" / "rgz" / "RgzParcelResolutionService.java"
    processor_path = REPOSITORY / "src" / "main" / "java" / "rs" / "sud" / "eaukcija" / "enrichment" / "EnrichmentItemProcessor.java"
    for path in (
        decision_path, previous_path, roadmap_path, readme_path,
        properties_path, migration_path, claim_migration_path,
        client_path, service_path, processor_path,
    ):
        check(path.is_file(), f"missing documentation: {path}")
    decision = decision_path.read_text(encoding="utf-8")
    previous = previous_path.read_text(encoding="utf-8")
    roadmap = roadmap_path.read_text(encoding="utf-8")
    readme = readme_path.read_text(encoding="utf-8")
    properties = properties_path.read_text(encoding="utf-8")
    migration = migration_path.read_text(encoding="utf-8")
    claim_migration = claim_migration_path.read_text(encoding="utf-8")
    client = client_path.read_text(encoding="utf-8")
    service = service_path.read_text(encoding="utf-8")
    processor = processor_path.read_text(encoding="utf-8")
    check("OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION" in decision)
    check("operator monitoring" in decision)
    check("228,010 RSD" in decision)
    check("No building resolver is" in decision)
    check("Keep #42 open pending a complete building contract review" in decision)
    check("06d95eb06d4d4a5408e8156df22a43082101affaf3b2b5ca4a6d6e6df87f8658" in decision)
    check("4de609f7f017295f2891d3e0219729e24f440dd7f89c4e8baa872bb3c06a4b48" in decision)
    check("2026-09-03-decision-41-rgz-automatic-geometry-access.md" in previous)
    check("#41 decision / #21 runtime" in roadmap)
    check("requires explicit current dataset/capabilities/schema pins" in roadmap)
    check("Automatic parcel resolution" in readme)
    check("rgz.enabled=${RGZ_ENABLED:false}" in properties)
    check("rgz.dataset-version=${RGZ_DATASET_VERSION:}" in properties)
    check("rgz.capabilities-sha256=${RGZ_CAPABILITIES_SHA256:}" in properties)
    check("rgz.schema-sha256=${RGZ_SCHEMA_SHA256:}" in properties)
    check("rgz.requests-per-second=${RGZ_REQUESTS_PER_SECOND:0.2}" in properties)
    check("rgz.max-concurrency=${RGZ_MAX_CONCURRENCY:1}" in properties)
    check("rgz.max-logical-lookups-per-run=${RGZ_MAX_LOGICAL_LOOKUPS_PER_RUN:100}" in properties)
    check("invalidate_stale_rgz_parcel_selection" in migration)
    check("CREATE TABLE rgz_enrichment_run_usage" in migration)
    check("CREATE TABLE rgz_enrichment_run_lookup_claims" in claim_migration)
    check("PRIMARY KEY (enrichment_run_id, input_fingerprint)" in claim_migration)
    check("MAX_FEATURES = 2" in client)
    check("RgzParcelResult.Status.ERROR, \"INVALID_CRS\"" in client)
    check("String returnedKoCode = textualNumber" in client)
    check("evidence.put(\"returnedKoCode\", returnedKoCode)" in client)
    check("evidence.put(\"returnedKoCode\", requestedKoCode)" not in client)
    check("TransactionTemplate" in service)
    check("rgz_enrichment_run_lookup_claims" in service)
    cache_migration = (migration_path.parent / "V21__rgz_cache_identity_and_retry_discovery.sql").read_text()
    check("CREATE TABLE rgz_parcel_cache_keys" in cache_migration)
    check("SELECT DISTINCT ON (input_fingerprint)" in cache_migration)
    check("ORDER BY input_fingerprint, cached_at, id" in cache_migration)
    check("rgz_parcel_cache_keys" in service)
    cache_method = service.split("private CacheRecord cache(", 1)[1].split("private CacheRecord persistCache(", 1)[0]
    check("getCapabilitiesSha256" not in cache_method)
    check("RESOLVER_VERSION" not in cache_method)
    check("cached.datasetSha256()" in service)
    check("if (!properties.isEnabled())" not in service)
    discovery = (REPOSITORY / "src/main/java/rs/sud/eaukcija/enrichment/EnrichmentRunRepository.java").read_text()
    check("pending_rgz AS" in discovery)
    check("row.parcelRetryPending()" in discovery)
    check("rgz.networkAllowed()" in discovery)
    check("handled.used_cache_record_id IS NOT NULL" in discovery)
    check("pg_advisory_xact_lock" not in service)
    check("@Transactional" not in service)
    check("TransactionSynchronizationManager.isActualTransactionActive()" in service)
    check("must not run inside a database transaction" in service)
    check("@Transactional" not in processor)
    check("stage.name() == EnrichmentStageName.PARCEL_PATH" in processor)
    check((ROOT / "downstream-issue-21.md").is_file())
    check((ROOT / "downstream-issue-42.md").is_file())


def main() -> None:
    source_review = read_json("reviewed-sources.json")
    wfs_evidence = read_json("sanitized-wfs-evidence.json")
    contract = read_json("automated-access-contract.json")
    for fixture in (source_review, wfs_evidence, contract):
        scan_redaction(fixture)
    verify_sources(source_review)
    verify_wfs(wfs_evidence)
    latest = wfs_evidence["current_recheck"]
    source_keys = {"regdkp-wfs-capabilities": "capabilities", "regdkp-parcel-schema": "parcel_schema",
                   "regdkp-object-schema": "object_schema"}
    for source in source_review["rechecks"]:
        check(source["raw_sha256"] == latest[source_keys[source["id"]]]["raw_sha256"])
    verify_contract(contract)
    verify_documentation()
    print(
        "issue #41/#21 evidence OK: automatic parcel WFS capability with "
        "explicit pinned activation; building contract pending; cache-first retry discovery"
    )


if __name__ == "__main__":
    main()
