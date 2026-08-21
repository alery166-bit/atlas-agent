#!/usr/bin/env python3
"""Run a non-intervention V1.2 Atlas business trial against the deployed API.

The runner never confirms or rejects evidence and never performs an operator
confirmation. It observes whether the current Agent can complete a report by
itself and records the exact boundary when human judgment is still required.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REVIEW_TERMINAL = {"SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED"}
TASK_FAILURE = {"SOURCE_FAILED", "MODEL_FAILED", "REPORT_FAILED", "CANCELLED"}
TASK_TERMINAL = TASK_FAILURE | {"COMPLETED", "WAITING_SUBJECT_CONFIRMATION"}


class ApiError(RuntimeError):
    pass


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def request(
    base_url: str,
    method: str,
    path: str,
    *,
    body: object | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = 360,
) -> tuple[bytes, dict[str, str], int]:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    merged_headers = dict(headers or {})
    if data is not None:
        merged_headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        method=method,
        headers=merged_headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.read(), dict(response.headers.items()), response.status
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", errors="replace")
        raise ApiError(f"{method} {path} -> HTTP {error.code}: {payload}") from error
    except urllib.error.URLError as error:
        raise ApiError(f"{method} {path} -> network error: {error}") from error


def request_json(
    base_url: str,
    method: str,
    path: str,
    *,
    body: object | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = 360,
    missing_ok: bool = False,
) -> Any:
    try:
        content, _, _ = request(
            base_url,
            method,
            path,
            body=body,
            headers=headers,
            timeout=timeout,
        )
    except ApiError as error:
        if missing_ok and "HTTP 404" in str(error):
            return None
        raise
    return json.loads(content.decode("utf-8")) if content else {}


def save_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def safe_filename(value: str) -> str:
    return "".join("_" if char in '<>:"/\\|?*' else char for char in value).strip()


def create_and_start(
    base_url: str,
    item: dict[str, Any],
    operator: str,
) -> dict[str, Any]:
    company = item["company_name"]
    print(f"[{company}] 创建真实业务试跑任务", flush=True)
    created = request_json(
        base_url,
        "POST",
        "/api/tasks",
        body={
            "prompt": (
                f"生成{company}的企业风险报告。请自动核对企业数据、公开信息和网络舆情，"
                "识别失联、拖欠工资、闭店、经营异常、司法与其他经营风险，完成证据研判、"
                "规则评分和DOCX报告；只有确实无法判断的证据才转人工。"
            ),
            "company_query": item["credit_code"],
        },
        headers={
            "Idempotency-Key": "v12-real-trial-" + uuid.uuid4().hex,
            "X-Operator-Id": operator,
        },
    )
    task_id = created["task_id"]
    started_at = now_iso()
    executed = request_json(
        base_url,
        "POST",
        f"/api/tasks/{task_id}/execute",
        headers={"X-Worker-Id": "v12-real-trial-worker"},
        timeout=360,
    )
    return {
        "sample": item,
        "task_id": task_id,
        "task_no": created["task_no"],
        "started_at": started_at,
        "initial_execution": executed,
        "poll_state": "RUNNING",
    }


def collect_task_detail(base_url: str, result: dict[str, Any]) -> dict[str, Any]:
    task_id = result["task_id"]
    task = request_json(base_url, "GET", f"/api/tasks/{task_id}")
    result["task"] = task
    result["workspace"] = request_json(
        base_url, "GET", f"/api/tasks/{task_id}/workspace", missing_ok=True
    )
    result["evidence"] = request_json(
        base_url,
        "GET",
        f"/api/tasks/{task_id}/public-intelligence/evidence",
        missing_ok=True,
    ) or []
    result["evidence_decisions"] = request_json(
        base_url,
        "GET",
        f"/api/tasks/{task_id}/public-intelligence/decisions",
        missing_ok=True,
    ) or []
    result["confirmed_events"] = request_json(
        base_url,
        "GET",
        f"/api/tasks/{task_id}/public-intelligence/confirmed-events",
        missing_ok=True,
    ) or []
    result["searches"] = request_json(
        base_url,
        "GET",
        f"/api/tasks/{task_id}/public-intelligence/searches",
        missing_ok=True,
    ) or []
    result["model_review"] = request_json(
        base_url,
        "GET",
        f"/api/tasks/{task_id}/public-intelligence/evidence/model-review/jobs/latest",
        missing_ok=True,
    )
    return result


def review_finished(result: dict[str, Any]) -> bool:
    review = result.get("model_review")
    return bool(review and review.get("status") in REVIEW_TERMINAL)


def classify(result: dict[str, Any]) -> str:
    task = result.get("task") or {}
    status = task.get("status")
    if status == "COMPLETED":
        return "AUTO_COMPLETED"
    if status == "WAITING_SUBJECT_CONFIRMATION":
        return "MANUAL_SUBJECT_CONFIRMATION"
    if status in TASK_FAILURE:
        return "FAILED"
    workspace = result.get("workspace") or {}
    if workspace.get("subject_data_conflicts"):
        return "MANUAL_SUBJECT_DATA_REVIEW"
    evidence = result.get("evidence") or []
    unverified = sum(item.get("verification_status") == "UNVERIFIED" for item in evidence)
    if status == "CALCULATING_RISK" and review_finished(result) and unverified > 0:
        return "MANUAL_EVIDENCE_REVIEW"
    if status == "WAITING_OPERATOR_CONFIRMATION":
        return "MANUAL_OPERATOR_CONFIRMATION"
    return "RUNNING"


def download_report(base_url: str, output_dir: Path, result: dict[str, Any]) -> None:
    if result.get("outcome") != "AUTO_COMPLETED":
        return
    task_id = result["task_id"]
    content, headers, _ = request(
        base_url,
        "GET",
        f"/api/tasks/{task_id}/reports/latest/download",
        timeout=360,
    )
    company = safe_filename(result["sample"]["company_name"])
    output = output_dir / "reports" / f"{company}_{result['task_no']}.docx"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(content)
    result["download"] = {
        "path": str(output.resolve()),
        "size_bytes": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
        "server_sha256": headers.get("X-Content-SHA256")
        or headers.get("X-content-sha256"),
    }


def evidence_summary(evidence: list[dict[str, Any]]) -> dict[str, Any]:
    relevance: dict[str, int] = {}
    verification: dict[str, int] = {}
    risk_types: dict[str, int] = {}
    confidences: list[float] = []
    for item in evidence:
        metadata = item.get("metadata") or {}
        rel = metadata.get("llm_relevance") or "NOT_REVIEWED"
        relevance[rel] = relevance.get(rel, 0) + 1
        status = item.get("verification_status") or "UNKNOWN"
        verification[status] = verification.get(status, 0) + 1
        risk_type = metadata.get("llm_risk_type") or item.get("risk_type") or "UNKNOWN"
        risk_types[risk_type] = risk_types.get(risk_type, 0) + 1
        try:
            confidences.append(float(metadata["llm_confidence"]))
        except (KeyError, TypeError, ValueError):
            pass
    return {
        "total": len(evidence),
        "relevance": relevance,
        "verification": verification,
        "risk_types": risk_types,
        "confidence_min": min(confidences) if confidences else None,
        "confidence_max": max(confidences) if confidences else None,
        "confidence_average": (
            round(sum(confidences) / len(confidences), 4) if confidences else None
        ),
        "confidence_ge_090": sum(value >= 0.90 for value in confidences),
        "confidence_ge_085": sum(value >= 0.85 for value in confidences),
        "confidence_ge_080": sum(value >= 0.80 for value in confidences),
    }


def compact_result(result: dict[str, Any]) -> dict[str, Any]:
    workspace = result.get("workspace") or {}
    snapshot = workspace.get("data_snapshot") or {}
    risk = workspace.get("risk_score") or {}
    review = result.get("model_review") or {}
    return {
        "company_name": result["sample"]["company_name"],
        "cohort": result["sample"].get("cohort"),
        "task_id": result["task_id"],
        "task_no": result["task_no"],
        "outcome": result.get("outcome"),
        "task_status": (result.get("task") or {}).get("status"),
        "error_code": (result.get("task") or {}).get("error_code"),
        "risk_event_count": len(snapshot.get("risk_events") or []),
        "company_change_count": len(snapshot.get("company_changes") or []),
        "evidence": evidence_summary(result.get("evidence") or []),
        "confirmed_event_count": len(result.get("confirmed_events") or []),
        "score": {
            "original": risk.get("original_score"),
            "manual": risk.get("manual_score"),
            "level": risk.get("manual_risk_level") or risk.get("original_risk_level"),
            "engine_version": risk.get("engine_version"),
        },
        "model": {
            "status": review.get("status"),
            "provider": review.get("provider"),
            "name": review.get("model"),
            "calls": review.get("model_call_count", 0),
            "prompt_tokens": review.get("prompt_token_count", 0),
            "completion_tokens": review.get("completion_token_count", 0),
            "total_tokens": review.get("total_token_count", 0),
            "suggested_score": review.get("model_suggested_score"),
        },
        "report": result.get("download"),
        "finished_at": result.get("finished_at"),
    }


def save_state(output_dir: Path, results: list[dict[str, Any]]) -> None:
    save_json(output_dir / "raw-results.json", results)
    save_json(output_dir / "trial-summary.json", [compact_result(item) for item in results])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://10.210.0.62:8301")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--operator", default="v12-real-trial")
    parser.add_argument("--start-index", type=int, default=0)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--poll-seconds", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume raw-results.json in output-dir without creating duplicate tasks",
    )
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    selected = manifest["companies"][args.start_index :]
    if args.limit is not None:
        selected = selected[: args.limit]
    if not selected:
        raise SystemExit("No trial companies selected")

    state_path = args.output_dir / "raw-results.json"
    if args.resume:
        if not state_path.exists():
            raise SystemExit(f"Cannot resume: {state_path} does not exist")
        results = json.loads(state_path.read_text(encoding="utf-8"))
        print(f"Resuming {len(results)} existing trial tasks", flush=True)
    else:
        results: list[dict[str, Any]] = []
        for item in selected:
            try:
                result = create_and_start(args.base_url, item, args.operator)
            except Exception as error:  # preserve partial batch evidence
                result = {
                    "sample": item,
                    "started_at": now_iso(),
                    "finished_at": now_iso(),
                    "poll_state": "FAILED_TO_START",
                    "outcome": "FAILED",
                    "error": str(error),
                    "task_id": "",
                    "task_no": "",
                }
            results.append(result)
            save_state(args.output_dir, results)

    deadline = time.monotonic() + args.timeout_seconds
    review_finished_seen: dict[str, float] = {}
    while any(item.get("outcome") in {None, "RUNNING"} for item in results):
        if time.monotonic() >= deadline:
            for item in results:
                if item.get("outcome") in {None, "RUNNING"}:
                    item["outcome"] = "TIMEOUT"
                    item["finished_at"] = now_iso()
            break
        for item in results:
            if not item.get("task_id") or item.get("outcome") not in {None, "RUNNING"}:
                continue
            try:
                collect_task_detail(args.base_url, item)
                outcome = classify(item)
                if outcome.startswith("MANUAL_") and review_finished(item):
                    first_seen = review_finished_seen.setdefault(
                        item["task_id"], time.monotonic()
                    )
                    if time.monotonic() - first_seen < 10:
                        outcome = "RUNNING"
                item["outcome"] = outcome
                if outcome != "RUNNING":
                    item["finished_at"] = now_iso()
                    download_report(args.base_url, args.output_dir, item)
                    print(
                        f"[{item['sample']['company_name']}] {item['task_no']} -> {outcome}",
                        flush=True,
                    )
            except Exception as error:
                item["last_poll_error"] = str(error)
        save_state(args.output_dir, results)
        if any(item.get("outcome") == "RUNNING" for item in results):
            time.sleep(args.poll_seconds)

    save_state(args.output_dir, results)
    summary = [compact_result(item) for item in results]
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
