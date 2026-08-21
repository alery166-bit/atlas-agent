#!/usr/bin/env python3
"""Generate contact, relation and asset bulk files for the selected ES company sample."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Callable


TASK_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = TASK_ROOT / "work" / "es-dev-100" / "manifest.json"
DEFAULT_SOURCE = TASK_ROOT / "data" / "company"
DEFAULT_OUTPUT = TASK_ROOT / "work" / "es-dev-100"
RELATION_INDEX = "atlas-company-relation-v1-000001"
CONTACT_INDEX = "atlas-company-contact-v1-000001"
ASSET_INDEX = "atlas-company-asset-v1-000001"


def text(value: Any) -> str | None:
    result = "" if value is None else str(value).strip()
    return result or None


def number(value: Any) -> float | None:
    raw = text(value)
    if raw is None:
        return None
    try:
        return float(raw.replace(",", ""))
    except ValueError:
        return None


def date(value: Any) -> str | None:
    raw = text(value)
    if raw and len(raw) >= 10 and raw[4] == "-" and raw[7] == "-":
        candidate = raw[:10]
        try:
            datetime.strptime(candidate, "%Y-%m-%d")
            return candidate
        except ValueError:
            return None
    return None


def timestamp(value: Any) -> str | None:
    raw = text(value)
    if raw and len(raw) >= 19 and raw[4] == "-" and raw[7] == "-":
        return raw[:10] + "T" + raw[11:19] + "+08:00"
    return None


def add(target: dict[str, Any], key: str, value: Any) -> None:
    if value is not None and value != "" and value != [] and value != {}:
        target[key] = value


def percent(raw: Any) -> tuple[str | None, float | None]:
    value = number(raw)
    if value is None:
        return None, None
    ratio_percent = value * 100 if abs(value) <= 1 else value
    display = f"{ratio_percent:.6f}".rstrip("0").rstrip(".") + "%"
    return display, ratio_percent


def stable_id(table: str, record_id: str) -> str:
    return hashlib.sha256(f"company_mysql:{table}:{record_id}".encode()).hexdigest()


def content_hash(document: dict[str, Any]) -> str:
    canonical = json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def base_document(
    row: dict[str, str], company: dict[str, Any], table: str, batch_id: str, ingested_at: str
) -> tuple[str, dict[str, Any]]:
    record_id = text(row.get("id"))
    if record_id is None:
        raise ValueError(f"{table} row is missing id")
    relation_id = stable_id(table, record_id)
    document: dict[str, Any] = {
        "relation_id": relation_id,
        "atlas_company_id": company["atlas_company_id"],
        "company": {
            "name": company["company_name"],
            "source_company_id": company["source_company_id"],
        },
        "source": {
            "system": "company_mysql",
            "table": table,
            "record_id": record_id,
            "company_id": company["source_company_id"],
        },
        "ingest": {
            "batch_id": batch_id,
            "schema_version": "1.0",
            "dedupe_key": f"company_mysql:{table}:{record_id}",
            "ingested_at": ingested_at,
            "deleted": False,
        },
    }
    add(document["company"], "credit_code", company.get("credit_code"))
    add(document["source"], "source_updated_at", timestamp(row.get("update_time")))
    return relation_id, document


def shareholder(row: dict[str, str], document: dict[str, Any]) -> None:
    document.update({"relation_type": "SHAREHOLDER", "direction": "INBOUND"})
    subject: dict[str, Any] = {"name": text(row.get("shareholder_name"))}
    add(subject, "entity_type", text(row.get("shareholder_type")))
    document["subject"] = subject
    ratio_raw, ratio_percent = percent(row.get("rate"))
    ownership: dict[str, Any] = {}
    add(ownership, "ratio_raw", ratio_raw)
    add(ownership, "ratio_percent", ratio_percent)
    add(ownership, "registered_amount_raw", text(row.get("capital")))
    add(ownership, "registered_amount", number(row.get("capital")))
    add(ownership, "paid_amount_raw", text(row.get("paid_capital")))
    add(ownership, "paid_amount", number(row.get("paid_capital")))
    add(ownership, "capital_date", date(row.get("capital_date")))
    add(document, "ownership", ownership)


def main_person(row: dict[str, str], document: dict[str, Any]) -> None:
    document.update({"relation_type": "MAIN_PERSON", "direction": "INBOUND"})
    document["subject"] = {
        "entity_type": "PERSON",
        "name": text(row.get("name")),
        "position": text(row.get("position")),
    }
    add(document, "extensions", {"weight": text(row.get("weight"))})


def core_person(row: dict[str, str], document: dict[str, Any]) -> None:
    document.update({"relation_type": "CORE_PERSON", "direction": "INBOUND"})
    subject: dict[str, Any] = {"entity_type": "PERSON", "name": text(row.get("name"))}
    add(subject, "position", text(row.get("position")))
    add(subject, "brief", text(row.get("brief")))
    document["subject"] = subject


def investment(row: dict[str, str], document: dict[str, Any]) -> None:
    document.update({"relation_type": "OUTBOUND_INVESTMENT", "direction": "OUTBOUND"})
    subject: dict[str, Any] = {"entity_type": "COMPANY", "name": text(row.get("company_name"))}
    add(subject, "entity_id", text(row.get("invested_company_id")))
    add(subject, "legal_representative", text(row.get("legal_personal")))
    document["subject"] = subject
    add(document, "status", text(row.get("registration_status")))
    add(document, "valid_from", date(row.get("open_date")))
    ratio_raw, ratio_percent = percent(row.get("investment_rate"))
    ownership: dict[str, Any] = {}
    add(ownership, "ratio_raw", ratio_raw)
    add(ownership, "ratio_percent", ratio_percent)
    add(ownership, "registered_amount_raw", text(row.get("investment_amount")))
    add(ownership, "registered_amount", number(row.get("investment_amount")))
    add(document, "ownership", ownership)


def branch(row: dict[str, str], document: dict[str, Any]) -> None:
    document.update({"relation_type": "BRANCH", "direction": "OUTBOUND"})
    subject: dict[str, Any] = {"entity_type": "COMPANY", "name": text(row.get("branch_name"))}
    add(subject, "entity_id", text(row.get("branch_id")))
    add(subject, "legal_representative", text(row.get("legal_personal")))
    document["subject"] = subject
    add(document, "status", text(row.get("registration_status")))


RELATION_TABLES: dict[str, Callable[[dict[str, str], dict[str, Any]], None]] = {
    "company_shareholder": shareholder,
    "company_main_person": main_person,
    "company_core_person": core_person,
    "company_investment": investment,
    "company_branch": branch,
}


def asset_base_document(
    row: dict[str, str], company: dict[str, Any], table: str, asset_type: str,
    batch_id: str, ingested_at: str
) -> tuple[str, dict[str, Any]]:
    record_id = text(row.get("id"))
    if record_id is None:
        raise ValueError(f"{table} row is missing id")
    asset_id = stable_id(table, record_id)
    document: dict[str, Any] = {
        "asset_id": asset_id,
        "atlas_company_id": company["atlas_company_id"],
        "company": {
            "name": company["company_name"],
            "source_company_id": company["source_company_id"],
        },
        "asset_type": asset_type,
        "source": {
            "system": "company_mysql",
            "table": table,
            "record_id": record_id,
            "company_id": company["source_company_id"],
        },
        "ingest": {
            "batch_id": batch_id,
            "schema_version": "1.0",
            "dedupe_key": f"company_mysql:{table}:{record_id}",
            "ingested_at": ingested_at,
            "deleted": False,
        },
    }
    add(document["company"], "credit_code", company.get("credit_code"))
    add(document["source"], "source_updated_at", timestamp(row.get("update_time")))
    return asset_id, document


def add_asset_extensions(document: dict[str, Any], **values: Any) -> None:
    extensions: dict[str, Any] = {}
    for key, value in values.items():
        add(extensions, key, text(value))
    add(document, "extensions", extensions)


def certificate(row: dict[str, str], document: dict[str, Any]) -> None:
    add(document, "name", text(row.get("name")) or text(row.get("code")) or "资质证书")
    add(document, "code", text(row.get("code")))
    add(document, "status", text(row.get("status")))
    add(document, "issuer", text(row.get("issued_by")))
    add(document, "publish_date", date(row.get("publish_date")))
    add(document, "end_date", date(row.get("expire_date")))


def trademark(row: dict[str, str], document: dict[str, Any]) -> None:
    add(document, "name", text(row.get("name")) or text(row.get("code")) or "商标")
    add(document, "number", text(row.get("code")))
    add(document, "classification_code", text(row.get("classify_code")))
    add(document, "classification_name", text(row.get("classify")) or text(row.get("category")))
    add(document, "status", text(row.get("status")))
    add(document, "applicant_names", text(row.get("applicant_name")))
    add(document, "agent_names", text(row.get("agent_name")))
    add(document, "application_date", date(row.get("applicant_date")))
    add(document, "register_date", date(row.get("register_date")))
    add(document, "start_date", date(row.get("start_date")))
    add(document, "end_date", date(row.get("end_date")))
    add(document, "image_uri", text(row.get("logo_url")))
    add_asset_extensions(
        document,
        applicant_en_name=row.get("applicant_en_name"),
        applicant_address=row.get("applicant_address"),
        category=row.get("category"),
    )


def patent(row: dict[str, str], document: dict[str, Any]) -> None:
    add(document, "name", text(row.get("name")) or text(row.get("code")) or "专利")
    add(document, "number", text(row.get("applicant_no")))
    add(document, "code", text(row.get("code")))
    add(document, "classification_code", text(row.get("category_code")))
    add(document, "classification_name", text(row.get("type")))
    add(document, "status", text(row.get("status")) or text(row.get("legal_status")))
    add(document, "description", text(row.get("desc")))
    add(document, "applicant_names", text(row.get("applicant_name")))
    add(document, "inventor_names", text(row.get("inventor_name")))
    add(document, "agent_names", text(row.get("agent_name")))
    add(document, "application_date", date(row.get("applicant_date")))
    add(document, "publish_date", date(row.get("publish_date")))
    add_asset_extensions(document, legal_status=row.get("legal_status"), address=row.get("address"))


def software_copyright(row: dict[str, str], document: dict[str, Any]) -> None:
    add(
        document,
        "name",
        text(row.get("name")) or text(row.get("simple_name"))
        or text(row.get("register_no")) or "软件著作权",
    )
    add(document, "short_name", text(row.get("simple_name")))
    add(document, "number", text(row.get("register_no")))
    add(document, "code", text(row.get("version_no")))
    add(document, "publish_date", date(row.get("publish_date")))
    add(document, "register_date", date(row.get("register_date")))


def standard(row: dict[str, str], document: dict[str, Any]) -> None:
    add(document, "name", text(row.get("standard_name")) or text(row.get("standard_no")) or "标准")
    add(document, "number", text(row.get("standard_no")))
    add(document, "classification_name", text(row.get("standard_level")))
    add(document, "status", text(row.get("status")))
    add(document, "publish_date", date(row.get("publish_date")))
    add_asset_extensions(
        document,
        standard_property=row.get("standard_property"),
        draft_unit=row.get("draft_unit"),
        drafter=row.get("drafter"),
    )


def honor(row: dict[str, str], document: dict[str, Any]) -> None:
    add(document, "name", text(row.get("title")) or "企业荣誉")
    add(document, "classification_name", text(row.get("type")))
    add(document, "status", text(row.get("level")))
    add(document, "publish_date", date(row.get("publish_date")))
    add(document, "issuer", text(row.get("issued_by")))
    add_asset_extensions(document, year=row.get("year"), source=row.get("source"))


def product_or_brand(row: dict[str, str], document: dict[str, Any]) -> None:
    add(document, "name", text(row.get("name")) or "产品或品牌")
    add(document, "description", text(row.get("desc")))
    add_asset_extensions(document, slogan=row.get("slogan"))


def financing(row: dict[str, str], document: dict[str, Any]) -> None:
    round_name = text(row.get("round"))
    add(document, "name", round_name or "融资事件")
    add(document, "classification_name", round_name)
    add(document, "applicant_names", text(row.get("investor")))
    add(document, "application_date", date(row.get("financing_date")))
    raw_amount = text(row.get("amount"))
    amount: dict[str, Any] = {}
    add(amount, "raw", raw_amount)
    add(amount, "value", number(raw_amount))
    add(document, "amount", amount)


def tax_credit(row: dict[str, str], document: dict[str, Any]) -> None:
    year = text(row.get("year"))
    level = text(row.get("level"))
    add(document, "name", f"{year}年度纳税信用" if year else "纳税信用")
    add(document, "classification_name", level)
    add(document, "status", level)
    add(document, "number", text(row.get("taxpayer")))
    add(document, "issuer", text(row.get("evaluation_office")))
    add_asset_extensions(document, year=year)


ASSET_TABLES: dict[str, tuple[str, Callable[[dict[str, str], dict[str, Any]], None]]] = {
    "company_certificate": ("CERTIFICATE", certificate),
    "company_trademark": ("TRADEMARK", trademark),
    "company_patent": ("PATENT", patent),
    "company_software_copyright": ("SOFTWARE_COPYRIGHT", software_copyright),
    "company_standard": ("STANDARD", standard),
    "company_honor": ("HONOR", honor),
    "company_business": ("PRODUCT_OR_BRAND", product_or_brand),
    "company_financing": ("FINANCING", financing),
    "company_tax_credit": ("TAX_CREDIT", tax_credit),
}


def mask_contact(contact_type: str, value: str) -> str:
    if contact_type == "PHONE" and len(value) >= 7:
        return value[:3] + "****" + value[-4:]
    if contact_type == "EMAIL" and "@" in value:
        local, domain = value.split("@", 1)
        return local[:1] + "***@" + domain
    return value


def contact_type(value: Any) -> str:
    raw = text(value) or "OTHER"
    return {"网址": "WEBSITE", "网站": "WEBSITE", "电话": "PHONE", "邮箱": "EMAIL"}.get(raw, raw)


def write_bulk(path: Path, pairs: list[tuple[dict[str, Any], dict[str, Any]]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as output:
        for action, document in pairs:
            output.write(json.dumps(action, ensure_ascii=False, separators=(",", ":")) + "\n")
            output.write(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--source-dir", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--batch-id", default="es-dev-100-v2-relations")
    parser.add_argument("--ingested-at", default=datetime.now().astimezone().isoformat(timespec="seconds"))
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    companies = {item["source_company_id"]: item for item in manifest["companies"]}
    relation_pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
    relation_counts: dict[str, int] = {}
    for table, transformer in RELATION_TABLES.items():
        count = 0
        with (args.source_dir / f"{table}.csv").open("r", encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream):
                company = companies.get(text(row.get("company_id")))
                if company is None:
                    continue
                relation_id, document = base_document(
                    row, company, table, args.batch_id, args.ingested_at
                )
                transformer(row, document)
                if not text(document.get("subject", {}).get("name")):
                    continue
                document["ingest"]["content_hash"] = content_hash(document)
                relation_pairs.append(({
                    "index": {"_index": RELATION_INDEX, "_id": relation_id,
                              "routing": company["atlas_company_id"]}
                }, document))
                count += 1
        relation_counts[table] = count

    contact_pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
    with (args.source_dir / "company_contact.csv").open("r", encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            company = companies.get(text(row.get("company_id")))
            value = text(row.get("contact_value"))
            record_id = text(row.get("id"))
            if company is None or value is None or record_id is None:
                continue
            normalized_type = contact_type(row.get("contact_type"))
            contact_id = stable_id("company_contact", record_id)
            document: dict[str, Any] = {
                "contact_id": contact_id,
                "atlas_company_id": company["atlas_company_id"],
                "company": {
                    "name": company["company_name"],
                    "source_company_id": company["source_company_id"],
                },
                "contact_type": normalized_type,
                "value": value,
                "masked_value": mask_contact(normalized_type, value),
                "value_hash": hashlib.sha256(value.encode()).hexdigest(),
                "validity": {"status": "UNVERIFIED", "failure_count": 0},
                "source": {
                    "system": "company_mysql", "table": "company_contact",
                    "record_id": record_id, "company_id": company["source_company_id"],
                },
                "ingest": {
                    "batch_id": args.batch_id, "schema_version": "1.0",
                    "dedupe_key": f"company_mysql:company_contact:{record_id}",
                    "ingested_at": args.ingested_at, "deleted": False,
                },
            }
            add(document["company"], "credit_code", company.get("credit_code"))
            add(document, "extension", text(row.get("contact_ext")))
            add(document["source"], "source_updated_at", timestamp(row.get("update_time")))
            document["ingest"]["content_hash"] = content_hash(document)
            contact_pairs.append(({
                "index": {"_index": CONTACT_INDEX, "_id": contact_id,
                          "routing": company["atlas_company_id"]}
            }, document))

    asset_pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
    asset_counts: dict[str, int] = {}
    for table, (asset_type, transformer) in ASSET_TABLES.items():
        count = 0
        with (args.source_dir / f"{table}.csv").open("r", encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream):
                company = companies.get(text(row.get("company_id")))
                if company is None:
                    continue
                asset_id, document = asset_base_document(
                    row, company, table, asset_type, args.batch_id, args.ingested_at
                )
                transformer(row, document)
                if not text(document.get("name")):
                    continue
                document["ingest"]["content_hash"] = content_hash(document)
                asset_pairs.append(({
                    "index": {"_index": ASSET_INDEX, "_id": asset_id,
                              "routing": company["atlas_company_id"]}
                }, document))
                count += 1
        asset_counts[table] = count

    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_bulk(args.output_dir / "company_relation.ndjson", relation_pairs)
    write_bulk(args.output_dir / "company_contact.ndjson", contact_pairs)
    write_bulk(args.output_dir / "company_asset.ndjson", asset_pairs)
    report = {
        "company_count": len(companies),
        "relation_count": len(relation_pairs),
        "relation_counts": relation_counts,
        "contact_count": len(contact_pairs),
        "asset_count": len(asset_pairs),
        "asset_counts": asset_counts,
        "relation_company_count": len({d["atlas_company_id"] for _, d in relation_pairs}),
        "contact_company_count": len({d["atlas_company_id"] for _, d in contact_pairs}),
        "asset_company_count": len({d["atlas_company_id"] for _, d in asset_pairs}),
    }
    (args.output_dir / "context-manifest.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
