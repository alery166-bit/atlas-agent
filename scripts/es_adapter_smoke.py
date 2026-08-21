#!/usr/bin/env python3
"""Validate that Atlas resolves and freezes company data through Elasticsearch."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_COMPANIES = (
    "北京简熹和食品有限公司",
    "乾道投资控股集团有限公司",
    "北京童程童慧科技有限公司",
)


def request_json(
    method: str,
    url: str,
    body: dict[str, object] | None = None,
    headers: dict[str, str] | None = None,
) -> dict[str, object]:
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    merged_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        merged_headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(
        url,
        data=payload,
        headers=merged_headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        details = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} returned HTTP {error.code}: {details}") from error


def validate_company(api_url: str, company: str, sequence: int) -> dict[str, object]:
    unique = f"es-adapter-{time.time_ns()}-{sequence}"
    headers = {
        "Idempotency-Key": unique,
        "X-Operator-Id": "es-adapter-smoke",
    }
    task = request_json(
        "POST",
        f"{api_url}/tasks",
        {
            "prompt": "核对企业工商信息、经营状况与风险事件并更新风险报告",
            "company_query": company,
            "previous_report_file_id": "report-v1",
        },
        headers,
    )
    task_id = str(task["task_id"])
    executed = request_json(
        "POST",
        f"{api_url}/tasks/{task_id}/execute",
        headers={"X-Worker-Id": "es-adapter-smoke"},
    )
    snapshot = request_json("GET", f"{api_url}/tasks/{task_id}/snapshot")

    facts = snapshot["company_facts"]
    if facts["source_system"] != "ELASTICSEARCH":
        raise RuntimeError(f"{company}: expected ELASTICSEARCH, got {facts['source_system']}")
    if facts["canonical_name"] != company:
        raise RuntimeError(
            f"{company}: resolved unexpected enterprise {facts['canonical_name']}"
        )
    statuses = snapshot["source_statuses"]
    failed_sources = [item for item in statuses if item["query_status"] == "FAILED"]
    if failed_sources:
        raise RuntimeError(f"{company}: failed ES sources: {failed_sources}")

    additional = facts.get("additional_fields", {})
    return {
        "company": company,
        "task_id": task_id,
        "task_status": executed["status"],
        "current_step": executed["current_step"],
        "source_system": facts["source_system"],
        "source_entity_id": facts["source_record_id"],
        "es_atlas_company_id": additional.get("atlasCompanyId"),
        "unified_credit_code": facts["unified_credit_code"],
        "registration_status": facts["registration_status"],
        "data_as_of": facts["data_as_of"],
        "company_changes": len(snapshot["company_changes"]),
        "risk_events": len(snapshot["risk_events"]),
        "contacts": int(additional.get("contactCount", "0")),
        "public_intelligence": int(additional.get("publicIntelCount", "0")),
        "source_statuses": [
            {
                "source_name": item["source_name"],
                "query_status": item["query_status"],
                "record_count": item["record_count"],
            }
            for item in statuses
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-url", default="http://127.0.0.1:8301/api")
    parser.add_argument("--company", action="append", dest="companies")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    results = [
        validate_company(args.api_url.rstrip("/"), company, index)
        for index, company in enumerate(args.companies or DEFAULT_COMPANIES, start=1)
    ]
    document = {
        "result": "PASS",
        "scope": "Elasticsearch subject resolution and frozen data snapshot",
        "companies": results,
        "known_next_stage": (
            "SEARCH_PUBLIC_INTELLIGENCE requires live provider configuration and is not "
            "executed by this smoke test"
        ),
    }
    text = json.dumps(document, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"ES adapter smoke test failed: {error}", file=sys.stderr)
        sys.exit(1)
