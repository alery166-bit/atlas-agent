#!/usr/bin/env python3
"""Build a deterministic 100-company Elasticsearch validation dataset.

The existing eight-company experiment is retained verbatim because it contains
high-coverage event, public-intelligence and contact documents. The remaining
company master documents are streamed from company_base.csv without loading the
multi-gigabyte company dataset into memory.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


TASK_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EXISTING = TASK_ROOT / "experiments" / "es-v1-offline-20260729" / "bulk" / "company.ndjson"
DEFAULT_SOURCE = TASK_ROOT / "data" / "company" / "company_base.csv"
DEFAULT_PINNED_SAMPLES = tuple(sorted((TASK_ROOT / "data" / "samples").glob("*.json")))
DEFAULT_OUTPUT_DIR = TASK_ROOT / "work" / "es-dev-100"
INDEX_NAME = "atlas-company-v1-000001"
UUID_NAMESPACE = uuid.UUID("278ce9c3-710b-4f50-b0b8-31ad26216412")


def text(value: Any) -> str | None:
    if value is None:
        return None
    result = str(value).strip()
    return result or None


def json_value(value: Any, fallback: Any) -> Any:
    raw = text(value)
    if raw is None:
        return fallback
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return fallback


def number(value: Any) -> float | None:
    raw = text(value)
    if raw is None:
        return None
    try:
        return float(raw.replace(",", ""))
    except ValueError:
        return None


def integer(value: Any) -> int | None:
    parsed = number(value)
    return int(parsed) if parsed is not None else None


def date(value: Any) -> str | None:
    raw = text(value)
    if raw is None:
        return None
    if len(raw) >= 10 and raw[4] == "-" and raw[7] == "-":
        return raw[:10]
    return None


def timestamp(value: Any) -> str | None:
    raw = text(value)
    if raw is None:
        return None
    if len(raw) >= 19 and raw[4] == "-" and raw[7] == "-":
        return raw[:10] + "T" + raw[11:19] + "+08:00"
    return None


def epoch_date(value: Any) -> str | None:
    parsed = number(value)
    if parsed is None:
        return None
    return datetime.fromtimestamp(parsed / 1000, timezone.utc).date().isoformat()


def epoch_timestamp(value: Any) -> str | None:
    parsed = number(value)
    if parsed is None:
        return None
    return datetime.fromtimestamp(parsed / 1000, timezone.utc).isoformat().replace("+00:00", "Z")


def add(target: dict[str, Any], key: str, value: Any) -> None:
    if value is not None and value != "" and value != [] and value != {}:
        target[key] = value


def capital_unit(raw_unit: Any) -> tuple[str | None, str | None]:
    unit = text(raw_unit)
    if unit is None:
        return None, None
    currency = None
    if "人民币" in unit:
        currency = "CNY"
    elif "港" in unit:
        currency = "HKD"
    elif "美元" in unit:
        currency = "USD"
    normalized = "万元" if unit.startswith("万") else unit
    return currency, normalized


def company_document(row: dict[str, str], batch_id: str, ingested_at: str) -> dict[str, Any]:
    source_company_id = text(row.get("company_id"))
    company_name = text(row.get("company_name"))
    if source_company_id is None or company_name is None:
        raise ValueError("company_id and company_name are required")

    atlas_company_id = str(uuid.uuid5(UUID_NAMESPACE, f"company_mysql:{source_company_id}"))
    document: dict[str, Any] = {"atlas_company_id": atlas_company_id}

    identity: dict[str, Any] = {"source_company_id": source_company_id}
    for source_key, target_key in (
        ("credit_code", "credit_code"),
        ("register_no", "register_no"),
        ("organization_code", "organization_code"),
        ("taxpayer_no", "taxpayer_no"),
        ("taxpayer_type", "taxpayer_type"),
    ):
        add(identity, target_key, text(row.get(source_key)))
    document["identity"] = identity

    names: dict[str, Any] = {"canonical": company_name}
    add(names, "short", text(row.get("short_name")))
    add(names, "english", text(row.get("english_name")))
    aliases = json_value(row.get("former_names"), [])
    if isinstance(aliases, list):
        add(names, "aliases", [str(item) for item in aliases if text(item)])
    document["name"] = names

    registration: dict[str, Any] = {}
    for source_key, target_key in (
        ("company_type", "company_type"),
        ("company_type_code", "company_type_code"),
        ("registration_status", "status"),
        ("legal_personal", "legal_representative"),
        ("registration_authority", "authority"),
        ("business_scope", "business_scope"),
    ):
        add(registration, target_key, text(row.get(source_key)))
    for source_key, target_key in (
        ("open_date", "open_date"),
        ("check_date", "check_date"),
        ("business_start", "business_start"),
        ("business_end", "business_end"),
        ("cancel_date", "cancel_date"),
        ("revocation_date", "revocation_date"),
    ):
        add(registration, target_key, date(row.get(source_key)))
    add(document, "registration", registration)

    registered_raw = text(row.get("register_capital"))
    paid_raw = text(row.get("paid_capital"))
    currency, unit = capital_unit(row.get("unit"))
    capital: dict[str, Any] = {}
    add(capital, "registered_raw", registered_raw)
    add(capital, "registered_value", number(registered_raw))
    add(capital, "paid_raw", paid_raw)
    add(capital, "paid_value", number(paid_raw))
    add(capital, "currency", currency)
    add(capital, "unit", unit)
    add(document, "capital", capital)

    addresses: dict[str, Any] = {}
    add(addresses, "registered", text(row.get("address")))
    add(addresses, "report", text(row.get("report_address")))
    add(addresses, "business", text(row.get("business_address")))
    add(addresses, "region_code", text(row.get("region")))
    location = text(row.get("location"))
    if location and "," in location:
        try:
            lon, lat = (float(part.strip()) for part in location.split(",", 1))
            addresses["geo"] = {"lon": lon, "lat": lat}
        except ValueError:
            pass
    add(document, "addresses", addresses)

    industry: dict[str, Any] = {}
    add(industry, "source_value", text(row.get("industry_v8")))
    add(industry, "industry_id", text(row.get("industry_id")))
    add(document, "industry", industry)

    profile: dict[str, Any] = {}
    add(profile, "brief", text(row.get("brief")))
    add(profile, "insured_num", integer(row.get("insured_num")))
    site = text(row.get("site"))
    add(profile, "websites", [site] if site else None)
    add(profile, "logo_uri", text(row.get("logo")))
    add(document, "business_profile", profile)

    control: dict[str, Any] = {}
    add(control, "actual_controller", text(row.get("actual_controller")))
    actual_path = json_value(row.get("actual_path"), None)
    add(control, "actual_path", actual_path)
    add(document, "control", control)

    risk_projection: dict[str, Any] = {}
    add(risk_projection, "legacy_score", number(row.get("risk_score")))
    labels = json_value(row.get("risk_label"), [])
    if isinstance(labels, list):
        add(risk_projection, "legacy_labels", [str(item) for item in labels if text(item)])
    overall_rating = json_value(row.get("overall_rating"), None)
    add(risk_projection, "legacy_overall_rating", overall_rating)
    add(document, "risk_projection", risk_projection)

    updated_at = timestamp(row.get("update_time"))
    if updated_at:
        document["freshness"] = {"business_updated_at": updated_at}

    source: dict[str, Any] = {"system": "company_mysql", "table": "company_base"}
    add(source, "record_id", text(row.get("id")))
    add(source, "source_updated_at", updated_at)
    document["source"] = source

    extensions: dict[str, Any] = {}
    add(extensions, "source_create_time", text(row.get("create_time")))
    add(extensions, "source_is_monitor", text(row.get("is_monitor")))
    add(document, "extensions", extensions)

    ingest = {
        "batch_id": batch_id,
        "schema_version": "1.0",
        "ingested_at": ingested_at,
        "deleted": False,
    }
    document["ingest"] = ingest
    canonical = json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    ingest["content_hash"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return document


def legacy_sample_document(path: Path, batch_id: str, ingested_at: str) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    hits = payload.get("hits", {}).get("hits", [])
    if len(hits) != 1:
        raise ValueError(f"pinned sample must contain exactly one hit: {path}")
    hit = hits[0]
    row = hit.get("_source", {})
    source_company_id = text(row.get("companyId")) or text(hit.get("_id"))
    company_name = text(row.get("fullName"))
    if source_company_id is None or company_name is None:
        raise ValueError(f"pinned sample is missing companyId/fullName: {path}")

    atlas_company_id = str(uuid.uuid5(UUID_NAMESPACE, f"legacy_es:{source_company_id}"))
    document: dict[str, Any] = {"atlas_company_id": atlas_company_id}

    identity: dict[str, Any] = {"source_company_id": source_company_id}
    for source_key, target_key in (
        ("socialIdentifier", "credit_code"),
        ("companyCode", "register_no"),
        ("organizingCode", "organization_code"),
        ("taxpayerIdentifier", "taxpayer_no"),
    ):
        add(identity, target_key, text(row.get(source_key)))
    document["identity"] = identity

    names: dict[str, Any] = {"canonical": company_name}
    add(names, "english", text(row.get("englishName")))
    document["name"] = names

    registration: dict[str, Any] = {}
    for source_key, target_key in (
        ("companyType", "company_type"),
        ("operateStatus", "status"),
        ("corporation", "legal_representative"),
        ("registerAuthority", "authority"),
        ("businessScope", "business_scope"),
    ):
        add(registration, target_key, text(row.get(source_key)))
    open_date = epoch_date(row.get("openDate"))
    add(registration, "open_date", open_date)
    add(registration, "check_date", epoch_date(row.get("checkDate")))
    add(registration, "business_start", open_date)
    business_term = text(row.get("businessTerm"))
    if business_term and "至" in business_term:
        add(registration, "business_end", date(business_term.split("至", 1)[1].strip()))
    add(document, "registration", registration)

    registered_raw = text(row.get("capital"))
    paid_raw = text(row.get("payedCapital"))
    currency, unit = capital_unit(row.get("unit"))
    capital: dict[str, Any] = {}
    add(capital, "registered_raw", registered_raw)
    add(capital, "registered_value", number(registered_raw))
    add(capital, "paid_raw", paid_raw)
    add(capital, "paid_value", number(paid_raw))
    add(capital, "currency", currency)
    add(capital, "unit", unit)
    add(document, "capital", capital)

    addresses: dict[str, Any] = {}
    add(addresses, "registered", text(row.get("address")))
    add(addresses, "report", text(row.get("qccAddress")))
    longitude = number(row.get("registerLongitude"))
    latitude = number(row.get("registerLatitude"))
    if longitude is not None and latitude is not None:
        addresses["geo"] = {"lon": longitude, "lat": latitude}
    add(document, "addresses", addresses)

    add(document, "industry", {"source_value": text(row.get("industry"))})
    profile: dict[str, Any] = {}
    add(profile, "insured_num", integer(row.get("insuredNum")))
    add(document, "business_profile", profile)
    add(document, "control", {"actual_controller": text(row.get("actualController"))})

    labels = row.get("riskLabel")
    risk_projection: dict[str, Any] = {}
    if isinstance(labels, list):
        add(risk_projection, "legacy_labels", [str(item) for item in labels if text(item)])
    add(document, "risk_projection", risk_projection)

    updated_at = epoch_timestamp(row.get("updateTime"))
    if updated_at:
        document["freshness"] = {"business_updated_at": updated_at}
    document["source"] = {
        "system": "legacy_es_sample",
        "table": text(hit.get("_index")) or "unknown",
        "record_id": text(hit.get("_id")) or source_company_id,
        **({"source_updated_at": updated_at} if updated_at else {}),
    }
    document["extensions"] = {"sample_file": path.name}
    ingest = {
        "batch_id": batch_id,
        "schema_version": "1.0",
        "ingested_at": ingested_at,
        "deleted": False,
    }
    document["ingest"] = ingest
    canonical = json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    ingest["content_hash"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return document


def read_existing(path: Path) -> tuple[list[tuple[dict[str, Any], dict[str, Any]]], set[str], set[str]]:
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(lines) % 2:
        raise ValueError(f"NDJSON must contain action/document pairs: {path}")
    pairs = []
    source_ids: set[str] = set()
    credit_codes: set[str] = set()
    for index in range(0, len(lines), 2):
        action = json.loads(lines[index])
        if not any(operation in action for operation in ("index", "create", "update", "delete")):
            action = {"index": action}
        document = json.loads(lines[index + 1])
        pairs.append((action, document))
        identity = document.get("identity", {})
        if identity.get("source_company_id"):
            source_ids.add(identity["source_company_id"])
        if identity.get("credit_code"):
            credit_codes.add(identity["credit_code"])
    return pairs, source_ids, credit_codes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--existing", type=Path, default=DEFAULT_EXISTING)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--batch-id", default="es-dev-100-v1")
    parser.add_argument("--ingested-at", default="2026-08-03T00:00:00+08:00")
    args = parser.parse_args()

    pairs, source_ids, credit_codes = read_existing(args.existing)
    if args.count < len(pairs):
        raise ValueError(f"count must be at least {len(pairs)}")

    pinned_names: list[str] = []
    for sample_path in DEFAULT_PINNED_SAMPLES:
        document = legacy_sample_document(sample_path, args.batch_id, args.ingested_at)
        identity = document["identity"]
        source_company_id = identity["source_company_id"]
        credit_code = identity.get("credit_code")
        if source_company_id in source_ids or (credit_code and credit_code in credit_codes):
            continue
        action = {"index": {"_index": INDEX_NAME, "_id": document["atlas_company_id"]}}
        pairs.append((action, document))
        pinned_names.append(document["name"]["canonical"])
        source_ids.add(source_company_id)
        if credit_code:
            credit_codes.add(credit_code)

    with args.source.open("r", encoding="utf-8-sig", newline="") as source_file:
        reader = csv.DictReader(source_file)
        for row in reader:
            if len(pairs) >= args.count:
                break
            source_company_id = text(row.get("company_id"))
            company_name = text(row.get("company_name"))
            credit_code = text(row.get("credit_code"))
            if source_company_id is None or company_name is None or source_company_id in source_ids:
                continue
            if credit_code and credit_code in credit_codes:
                continue
            document = company_document(row, args.batch_id, args.ingested_at)
            action = {"index": {"_index": INDEX_NAME, "_id": document["atlas_company_id"]}}
            pairs.append((action, document))
            source_ids.add(source_company_id)
            if credit_code:
                credit_codes.add(credit_code)

    if len(pairs) != args.count:
        raise RuntimeError(f"requested {args.count} companies, generated {len(pairs)}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    output_path = args.output_dir / "company.ndjson"
    with output_path.open("w", encoding="utf-8", newline="\n") as output_file:
        for action, document in pairs:
            output_file.write(json.dumps(action, ensure_ascii=False, separators=(",", ":")) + "\n")
            output_file.write(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n")

    digest = hashlib.sha256(output_path.read_bytes()).hexdigest()
    manifest = {
        "schema_version": "1.0",
        "batch_id": args.batch_id,
        "company_count": len(pairs),
        "detailed_company_count": 8,
        "master_only_company_count": len(pairs) - 8,
        "pinned_sample_count": len(pinned_names),
        "pinned_sample_names": pinned_names,
        "company_bulk": str(output_path.relative_to(TASK_ROOT)).replace("\\", "/"),
        "sha256": digest,
        "companies": [
            {
                "atlas_company_id": document["atlas_company_id"],
                "source_company_id": document["identity"]["source_company_id"],
                "credit_code": document["identity"].get("credit_code"),
                "company_name": document["name"]["canonical"],
            }
            for _, document in pairs
        ],
    }
    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"company_count": len(pairs), "bulk": str(output_path), "sha256": digest}, ensure_ascii=False))


if __name__ == "__main__":
    main()
