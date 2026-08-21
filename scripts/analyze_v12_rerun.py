#!/usr/bin/env python3
"""Aggregate V1.2 real-trial outcomes and validate downloaded DOCX packages."""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any


FORBIDDEN_TEST_MARKERS = [
    "测试风险摘要",
    "news.example.com",
    "运营人员取样核验的网页正文显示",
]


def inspect_docx(path: Path, company_name: str, credit_code: str) -> dict[str, Any]:
    content = path.read_bytes()
    with zipfile.ZipFile(path, "r") as archive:
        broken = archive.testzip()
        if broken:
            raise RuntimeError(f"{path.name}: DOCX CRC failed at {broken}")
        xml = archive.read("word/document.xml").decode("utf-8", errors="replace")
    markers = [marker for marker in FORBIDDEN_TEST_MARKERS if marker in xml]
    return {
        "path": str(path.resolve()),
        "package_valid": True,
        "company_name_present": company_name in xml,
        "credit_code_present": credit_code in xml,
        "forbidden_test_markers": markers,
        "sha256": hashlib.sha256(content).hexdigest(),
        "structural_pass": company_name in xml and credit_code in xml and not markers,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-results", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    results = json.loads(args.raw_results.read_text(encoding="utf-8"))
    outcomes = Counter(item.get("outcome") or "UNKNOWN" for item in results)
    verification = Counter()
    relevance = Counter()
    risk_types = Counter()
    model = Counter()
    report_checks: list[dict[str, Any]] = []
    rows: list[dict[str, Any]] = []

    for item in results:
        evidence = item.get("evidence") or []
        for value in evidence:
            metadata = value.get("metadata") or {}
            verification[value.get("verification_status") or "UNKNOWN"] += 1
            relevance[metadata.get("llm_relevance") or "NOT_REVIEWED"] += 1
            risk_types[metadata.get("llm_risk_type") or value.get("risk_type") or "UNKNOWN"] += 1

        review = item.get("model_review") or {}
        model["calls"] += int(review.get("model_call_count") or 0)
        model["prompt_tokens"] += int(review.get("prompt_token_count") or 0)
        model["completion_tokens"] += int(review.get("completion_token_count") or 0)
        model["total_tokens"] += int(review.get("total_token_count") or 0)

        download = item.get("download")
        check = None
        if download:
            path = Path(download["path"])
            check = inspect_docx(
                path,
                item["sample"]["company_name"],
                item["sample"]["credit_code"],
            )
            check["company_name"] = item["sample"]["company_name"]
            check["local_hash_matches_runner"] = check["sha256"] == download.get("sha256")
            check["server_hash_matches"] = (
                not download.get("server_sha256")
                or check["sha256"] == download.get("server_sha256")
            )
            check["full_pass"] = (
                check["structural_pass"]
                and check["local_hash_matches_runner"]
                and check["server_hash_matches"]
            )
            report_checks.append(check)

        workspace = item.get("workspace") or {}
        risk = workspace.get("risk_score") or {}
        rows.append({
            "company_name": item["sample"]["company_name"],
            "task_no": item.get("task_no"),
            "outcome": item.get("outcome"),
            "task_status": (item.get("task") or {}).get("status"),
            "risk_event_count": len((workspace.get("data_snapshot") or {}).get("risk_events") or []),
            "evidence_count": len(evidence),
            "unverified_evidence_count": sum(
                value.get("verification_status") == "UNVERIFIED" for value in evidence
            ),
            "original_score": risk.get("original_score"),
            "risk_level": risk.get("manual_risk_level") or risk.get("original_risk_level"),
            "confirmation_state": workspace.get("confirmation_state"),
            "next_action": workspace.get("next_action"),
            "readiness_blockers": workspace.get("readiness_blockers") or [],
            "subject_data_conflicts": workspace.get("subject_data_conflicts") or [],
            "model_calls": int(review.get("model_call_count") or 0),
            "total_tokens": int(review.get("total_token_count") or 0),
            "report_generated": bool(download),
            "report_check_pass": None if check is None else check["full_pass"],
        })

    evidence_total = sum(verification.values())
    automatically_decided = verification["CONFIRMED"] + verification["REJECTED"]
    summary = {
        "schema_version": "atlas-v1.2-rerun-summary.v1",
        "sample_count": len(results),
        "outcomes": dict(outcomes),
        "auto_completion_rate": round(outcomes["AUTO_COMPLETED"] / len(results), 4),
        "manual_intervention_rate": round(
            sum(count for key, count in outcomes.items() if key.startswith("MANUAL_"))
            / len(results),
            4,
        ),
        "technical_failure_count": outcomes["FAILED"] + outcomes["TIMEOUT"],
        "evidence": {
            "total": evidence_total,
            "verification": dict(verification),
            "relevance": dict(relevance),
            "risk_types": dict(risk_types),
            "automatically_decided": automatically_decided,
            "automatic_decision_rate": (
                round(automatically_decided / evidence_total, 4) if evidence_total else None
            ),
        },
        "model_usage": dict(model),
        "reports": {
            "generated": len(report_checks),
            "all_packages_valid": all(check["package_valid"] for check in report_checks),
            "all_identity_fields_present": all(
                check["company_name_present"] and check["credit_code_present"]
                for check in report_checks
            ),
            "all_hashes_match": all(
                check["local_hash_matches_runner"] and check["server_hash_matches"]
                for check in report_checks
            ),
            "all_structural_checks_pass": all(check["full_pass"] for check in report_checks),
            "checks": report_checks,
        },
        "tasks": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
