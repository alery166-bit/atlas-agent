#!/usr/bin/env python3
"""Install Atlas ES templates, import the 100-company sample and verify it."""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


TASK_ROOT = Path(__file__).resolve().parents[1]
TEMPLATE_ROOT = TASK_ROOT / "design" / "es-v1" / "index-templates"
SAMPLE_ROOT = TASK_ROOT / "work" / "es-dev-100"
EXPERIMENT_BULK = TASK_ROOT / "experiments" / "es-v1-offline-20260729" / "bulk"

INDEXES = {
    "atlas-company-v1-000001": ("atlas-company-read", "atlas-company-write"),
    "atlas-company-event-v1-000001": ("atlas-company-event-read", "atlas-company-event-write"),
    "atlas-public-intel-v1-000001": ("atlas-public-intel-read", "atlas-public-intel-write"),
    "atlas-company-contact-v1-000001": ("atlas-company-contact-read", "atlas-company-contact-write"),
    "atlas-company-relation-v1-000001": ("atlas-company-relation-read", "atlas-company-relation-write"),
    "atlas-company-asset-v1-000001": ("atlas-company-asset-read", "atlas-company-asset-write"),
}

BULKS = {
    "company": SAMPLE_ROOT / "company.ndjson",
    "company_event": EXPERIMENT_BULK / "company_event.ndjson",
    "public_intel": EXPERIMENT_BULK / "public_intel.ndjson",
    "company_contact": SAMPLE_ROOT / "company_contact.ndjson",
    "company_relation": SAMPLE_ROOT / "company_relation.ndjson",
    "company_asset": SAMPLE_ROOT / "company_asset.ndjson",
}

REQUIRED_COMPANY_NAMES = (
    "北京简熹和食品有限公司",
    "北京童程童慧科技有限公司",
    "乾道投资控股集团有限公司",
)


class EsError(RuntimeError):
    pass


def request(base_url: str, path: str, method: str = "GET", body: Any = None, content_type: str = "application/json") -> Any:
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
    target = base_url.rstrip("/") + path
    req = urllib.request.Request(target, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            payload = response.read()
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", errors="replace")
        raise EsError(f"{method} {path} failed with HTTP {exc.code}: {payload[:2000]}") from exc
    if not payload:
        return None
    return json.loads(payload)


def wait_until_ready(base_url: str) -> dict[str, Any]:
    last_error = None
    for _ in range(60):
        try:
            return request(base_url, "/_cluster/health?wait_for_status=yellow&timeout=5s")
        except Exception as exc:  # noqa: BLE001 - report final connection error
            last_error = exc
            time.sleep(2)
    raise EsError(f"Elasticsearch did not become ready: {last_error}")


def install_templates(base_url: str) -> list[str]:
    installed = []
    for template_path in sorted(TEMPLATE_ROOT.glob("*.json")):
        template_name = template_path.stem
        request(base_url, f"/_index_template/{template_name}", "PUT", json.loads(template_path.read_text(encoding="utf-8")))
        installed.append(template_name)
    return installed


def reset_indexes(base_url: str) -> None:
    for index_name in INDEXES:
        try:
            request(base_url, f"/{index_name}", "DELETE")
        except EsError as exc:
            if "index_not_found_exception" not in str(exc):
                raise


def create_indexes(base_url: str) -> None:
    for index_name, (read_alias, write_alias) in INDEXES.items():
        body = {
            "settings": {"number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "1s"},
            "aliases": {read_alias: {}, write_alias: {"is_write_index": True}},
        }
        request(base_url, f"/{index_name}", "PUT", body)


def action_count(path: Path) -> int:
    line_count = sum(1 for line in path.open("r", encoding="utf-8") if line.strip())
    if line_count % 2:
        raise EsError(f"Bulk file does not contain action/document pairs: {path}")
    return line_count // 2


def bulk_company_ids(path: Path) -> set[str]:
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(lines) % 2:
        raise EsError(f"Bulk file does not contain action/document pairs: {path}")
    company_ids: set[str] = set()
    for index in range(1, len(lines), 2):
        document = json.loads(lines[index])
        company_id = document.get("atlas_company_id")
        if company_id:
            company_ids.add(company_id)
    return company_ids


def normalize_bulk(path: Path) -> bytes:
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(lines) % 2:
        raise EsError(f"Bulk file does not contain action/document pairs: {path}")
    output: list[str] = []
    for index in range(0, len(lines), 2):
        action = json.loads(lines[index])
        if not any(operation in action for operation in ("index", "create", "update", "delete")):
            action = {"index": action}
        output.append(json.dumps(action, ensure_ascii=False, separators=(",", ":")))
        output.append(lines[index + 1])
    return ("\n".join(output) + "\n").encode("utf-8")


def import_bulk(base_url: str, name: str, path: Path, output_dir: Path) -> int:
    if not path.is_file():
        raise EsError(f"Missing bulk file: {path}")
    response = request(base_url, "/_bulk?refresh=wait_for", "POST", normalize_bulk(path), "application/x-ndjson")
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / f"bulk-response-{name}.json").write_text(
        json.dumps(response, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    if response.get("errors"):
        failures = []
        for item in response.get("items", []):
            operation = next(iter(item.values()))
            if operation.get("error"):
                failures.append(operation.get("error"))
            if len(failures) >= 5:
                break
        raise EsError(f"Bulk import {name} contains errors: {json.dumps(failures, ensure_ascii=False)}")
    return action_count(path)


def count(base_url: str, alias: str) -> int:
    return int(request(base_url, f"/{alias}/_count")["count"])


def exact_company_name_count(base_url: str, company_name: str) -> int:
    response = request(
        base_url,
        "/atlas-company-read/_count",
        "POST",
        {"query": {"term": {"name.canonical.raw": company_name}}},
    )
    return int(response["count"])


def first_event_identity() -> tuple[str, str]:
    path = BULKS["company_event"]
    with path.open("r", encoding="utf-8") as stream:
        action = json.loads(stream.readline())
        document = json.loads(stream.readline())
    if any(operation in action for operation in ("index", "create", "update", "delete")):
        metadata = next(iter(action.values()))
    else:
        metadata = action
    return metadata["routing"], document["atlas_company_id"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=os.environ.get("ATLAS_ES_URL", "http://127.0.0.1:9200"))
    parser.add_argument("--reset", action="store_true", help="delete only the six atlas *-v1-000001 validation indexes")
    parser.add_argument("--output", type=Path, default=SAMPLE_ROOT / "validation-result.json")
    args = parser.parse_args()

    initial_health = wait_until_ready(args.url)
    root = request(args.url, "/")
    templates = install_templates(args.url)
    if args.reset:
        reset_indexes(args.url)
    create_indexes(args.url)

    company_count = action_count(BULKS["company"])
    if company_count != 100:
        raise EsError(f"validation set must contain exactly 100 companies, found {company_count}")
    company_ids = bulk_company_ids(BULKS["company"])
    linked_company_ids = set().union(
        *(bulk_company_ids(BULKS[name]) for name in (
            "company_event", "public_intel", "company_contact", "company_relation",
            "company_asset"
        ))
    )
    missing_company_ids = linked_company_ids - company_ids
    if missing_company_ids:
        raise EsError(f"validation data references companies outside the sample: {sorted(missing_company_ids)}")

    imported = {name: import_bulk(args.url, name, path, args.output.parent) for name, path in BULKS.items()}
    expected = {
        "atlas-company-read": imported["company"],
        "atlas-company-event-read": imported["company_event"],
        "atlas-public-intel-read": imported["public_intel"],
        "atlas-company-contact-read": imported["company_contact"],
        "atlas-company-relation-read": imported["company_relation"],
        "atlas-company-asset-read": imported["company_asset"],
    }
    actual = {alias: count(args.url, alias) for alias in expected}
    if actual != expected:
        raise EsError(f"count mismatch: expected={expected}, actual={actual}")
    required_company_counts = {
        company_name: exact_company_name_count(args.url, company_name)
        for company_name in REQUIRED_COMPANY_NAMES
    }
    if any(value != 1 for value in required_company_counts.values()):
        raise EsError(f"required company lookup mismatch: {required_company_counts}")

    company_sample = request(
        args.url,
        "/atlas-company-read/_search",
        "POST",
        {"size": 3, "sort": [{"name.canonical.raw": "asc"}], "query": {"match_all": {}}},
    )
    routing, company_id = first_event_identity()
    query_string = urllib.parse.urlencode({"routing": routing})
    event_sample = request(
        args.url,
        f"/atlas-company-event-read/_search?{query_string}",
        "POST",
        {
            "size": 3,
            "sort": [{"event_date": {"order": "desc", "unmapped_type": "date"}}],
            "query": {"term": {"atlas_company_id": company_id}},
        },
    )
    final_health = request(args.url, "/_cluster/health?wait_for_status=green&timeout=30s")
    index_stats = request(args.url, "/_cat/indices/atlas-*?format=json&bytes=kb")

    report = {
        "status": "passed",
        "elasticsearch_version": root["version"]["number"],
        "cluster_name": root["cluster_name"],
        "initial_health": initial_health["status"],
        "final_health": final_health["status"],
        "templates": templates,
        "sample_company_count": company_count,
        "linked_company_count": len(linked_company_ids),
        "expected_counts": expected,
        "actual_counts": actual,
        "required_company_counts": required_company_counts,
        "company_sample_names": [
            hit["_source"]["name"]["canonical"] for hit in company_sample["hits"]["hits"]
        ],
        "routed_event_company_id": company_id,
        "routed_event_total": event_sample["hits"]["total"]["value"],
        "indexes": index_stats,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
