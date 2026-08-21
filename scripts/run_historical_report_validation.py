#!/usr/bin/env python3
"""Validate historical DOCX intake and advance resolvable V1 tasks to operator review."""

from __future__ import annotations

import argparse
import json
import mimetypes
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


TERMINAL_REVIEW_STATES = {"SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED"}


class ApiError(RuntimeError):
    pass


def request_json(
    base_url: str,
    method: str,
    path: str,
    *,
    body: object | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = 300,
) -> object:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    merged = dict(headers or {})
    if data is not None:
        merged.setdefault("Content-Type", "application/json")
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        method=method,
        headers=merged,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            content = response.read()
            return json.loads(content.decode("utf-8")) if content else {}
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", errors="replace")
        raise ApiError(f"{method} {path} -> HTTP {error.code}: {payload}") from error
    except urllib.error.URLError as error:
        raise ApiError(f"{method} {path} -> network error: {error}") from error


def upload_docx(base_url: str, path: Path) -> dict[str, object]:
    boundary = "----AtlasHistorical" + uuid.uuid4().hex
    mime = mimetypes.guess_type(path.name)[0] or (
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode("utf-8")
    suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/files/previous-reports",
        data=prefix + path.read_bytes() + suffix,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", errors="replace")
        raise ApiError(f"upload {path.name} -> HTTP {error.code}: {payload}") from error


def credit_code(report: dict[str, object]) -> str | None:
    fields = report.get("company_fields") or {}
    for key in ("统一社会信用代码", "统一社会信用代码/注册号", "信用代码"):
        value = str(fields.get(key) or "").strip()
        if value:
            return value
    return None


def resolve_company(base_url: str, query: str) -> dict[str, object]:
    encoded = urllib.parse.quote(query)
    return request_json(base_url, "GET", f"/api/companies/resolve?query={encoded}")


def create_task(
    base_url: str,
    company_query: str,
    file_id: str,
    operator: str,
    purpose: str,
) -> dict[str, object]:
    return request_json(
        base_url,
        "POST",
        "/api/tasks",
        body={
            "prompt": purpose,
            "company_query": company_query,
            "previous_report_file_id": file_id,
        },
        headers={
            "Idempotency-Key": "historical-validation-" + uuid.uuid4().hex,
            "X-Operator-Id": operator,
        },
    )


def advance_to_evidence_review(
    base_url: str,
    task_id: str,
) -> dict[str, object]:
    state: dict[str, object] = {}
    for _ in range(8):
        state = request_json(
            base_url,
            "POST",
            f"/api/tasks/{task_id}/execute",
            headers={"X-Worker-Id": "historical-validation-worker"},
            timeout=360,
        )
        status = str(state.get("status") or "")
        if status in {
            "CALCULATING_RISK",
            "FAILED",
            "WAITING_SUBJECT_CONFIRMATION",
            "WAITING_OPERATOR_CONFIRMATION",
            "COMPLETED",
        }:
            return state
    return state


def run_model_review(
    base_url: str,
    task_id: str,
    evidence_count: int,
) -> dict[str, object] | None:
    if evidence_count == 0:
        return None
    job = request_json(
        base_url,
        "POST",
        f"/api/tasks/{task_id}/public-intelligence/evidence/model-review",
        body={},
    )
    review_job_id = str(job["review_job_id"])
    print(f"    模型作业 {review_job_id} 已启动", flush=True)
    for attempt in range(120):
        latest = request_json(
            base_url,
            "GET",
            f"/api/tasks/{task_id}/public-intelligence/evidence/model-review/jobs/latest",
        )
        if str(latest.get("status")) in TERMINAL_REVIEW_STATES:
            return latest
        if attempt % 5 == 0:
            print(
                "    模型进度 "
                f"{latest.get('processed_count', 0)}/{latest.get('total_count', 0)}",
                flush=True,
            )
        time.sleep(5)
    raise TimeoutError(f"Model review did not finish for task {task_id}")


def save_result(output: Path, result: dict[str, object]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://10.210.0.62:8301")
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=Path("data/historical-reports/incoming"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("work/historical-reports/remote-validation-20260811.json"),
    )
    parser.add_argument("--operator", default="historical-validation")
    args = parser.parse_args()

    result: dict[str, object] = {
        "base_url": args.base_url,
        "operator": args.operator,
        "reports": [],
        "negative_gate_task": None,
    }
    uploaded: list[dict[str, object]] = []
    for index, path in enumerate(sorted(args.input_dir.glob("*.docx")), start=1):
        print(f"[{index}/13] 上传并解析 {path.name}", flush=True)
        upload = upload_docx(args.base_url, path)
        report = upload["parsed_report"]
        item: dict[str, object] = {
            "filename": path.name,
            "file_id": upload["file_id"],
            "report_type": report["report_type"],
            "supported_for_update": report["supported_for_update"],
            "company_name": report.get("company_name"),
            "credit_code": credit_code(report),
            "parse_warnings": report.get("parse_warnings", []),
            "source_content_sha256": report["source_content_sha256"],
        }
        uploaded.append(item)
        result["reports"].append(item)
        save_result(args.output, result)

    unsupported = [item for item in uploaded if not item["supported_for_update"]]
    if unsupported:
        sample = unsupported[0]
        print("验证非V1报告服务端阻断", flush=True)
        task = create_task(
            args.base_url,
            str(sample["company_name"]),
            str(sample["file_id"]),
            args.operator,
            "验证非V1历史报告必须在外部查询前停止",
        )
        task_state = advance_to_evidence_review(args.base_url, str(task["task_id"]))
        result["negative_gate_task"] = task_state
        save_result(args.output, result)

    for item in uploaded:
        if not item["supported_for_update"]:
            continue
        company_name = str(item["company_name"])
        query = str(item["credit_code"] or company_name)
        resolution = resolve_company(args.base_url, query)
        item["resolution_status"] = resolution.get("status")
        item["candidate_count"] = len(resolution.get("candidates") or [])
        if resolution.get("status") != "UNIQUE":
            item["workflow_status"] = "BLOCKED_COMPANY_NOT_IN_ES"
            print(f"[{company_name}] ES中无唯一主体，保留为待补数据", flush=True)
            save_result(args.output, result)
            continue

        print(f"[{company_name}] 创建并执行真实链路任务", flush=True)
        task = create_task(
            args.base_url,
            query,
            str(item["file_id"]),
            args.operator,
            f"更新{company_name}企业风险监测分析报告；完成模型辅助后等待运营确认",
        )
        item["task_id"] = task["task_id"]
        item["task_no"] = task["task_no"]
        state = advance_to_evidence_review(args.base_url, str(task["task_id"]))
        item["workflow_status"] = state.get("status")
        item["workflow_error_code"] = state.get("error_code")
        if state.get("status") != "CALCULATING_RISK":
            print(f"    任务停止于 {state.get('status')}", flush=True)
            save_result(args.output, result)
            continue

        evidence = request_json(
            args.base_url,
            "GET",
            f"/api/tasks/{task['task_id']}/public-intelligence/evidence",
        )
        item["evidence_count"] = len(evidence)
        print(f"    Tavily保留 {len(evidence)} 条候选证据", flush=True)
        review = run_model_review(args.base_url, str(task["task_id"]), len(evidence))
        item["model_review"] = review
        item["operator_boundary"] = (
            "REVIEW_EVIDENCE_AND_CONFIRM_SCORE"
            if evidence
            else "CONFIRM_NO_PUBLIC_EVIDENCE_AND_SCORE"
        )
        save_result(args.output, result)

    supported = [item for item in uploaded if item["supported_for_update"]]
    result["summary"] = {
        "report_count": len(uploaded),
        "supported_count": len(supported),
        "unsupported_count": len(unsupported),
        "resolvable_count": sum(item.get("resolution_status") == "UNIQUE" for item in supported),
        "operator_review_ready_count": sum(
            bool(item.get("operator_boundary")) for item in supported
        ),
        "blocked_company_count": sum(
            item.get("workflow_status") == "BLOCKED_COMPANY_NOT_IN_ES"
            for item in supported
        ),
    }
    save_result(args.output, result)
    print(json.dumps(result["summary"], ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
