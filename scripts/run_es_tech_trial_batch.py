#!/usr/bin/env python3
"""Run isolated ES technical-trial report updates through the deployed API."""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import sys
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path


REJECTION_REASON = (
    "ES技术试跑未执行LLM或人工语义研判，公开检索结果全部排除，不纳入风险评分"
)
STALE_REFERENCE_MARKERS = [
    "北京简熹和食品有限公司",
    "葛雪",
    "91110113MAK5DEJQ0W",
    "110113046525829",
    "北京市朝阳区东三环中路39号院6号楼15层1803-02",
]


class ApiError(RuntimeError):
    pass


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def request(
    base_url: str,
    method: str,
    path: str,
    *,
    body: object | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = 120,
) -> tuple[bytes, dict[str, str], int]:
    url = base_url.rstrip("/") + path
    data = None
    merged_headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        merged_headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, method=method, headers=merged_headers)
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
    timeout: int = 120,
) -> object:
    content, _, _ = request(
        base_url, method, path, body=body, headers=headers, timeout=timeout
    )
    return json.loads(content.decode("utf-8"))


def upload_docx(base_url: str, path: Path) -> dict[str, object]:
    boundary = "----AtlasTrial" + uuid.uuid4().hex
    filename = path.name
    mime = mimetypes.guess_type(filename)[0] or (
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    content = path.read_bytes()
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode("utf-8")
    suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/api/files/previous-reports",
        data=prefix + content + suffix,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", errors="replace")
        raise ApiError(f"upload {filename} -> HTTP {error.code}: {payload}") from error
    except urllib.error.URLError as error:
        raise ApiError(f"upload {filename} -> network error: {error}") from error


def visible_docx_text(path: Path) -> str:
    with zipfile.ZipFile(path, "r") as archive:
        broken = archive.testzip()
        if broken:
            raise RuntimeError(f"DOCX CRC failed at {broken}")
        parts = [
            name
            for name in archive.namelist()
            if name.endswith(".xml") and (
                name == "word/document.xml"
                or name.startswith("word/header")
                or name.startswith("word/footer")
            )
        ]
        return "\n".join(
            archive.read(name).decode("utf-8", errors="replace") for name in parts
        )


def run_company(
    base_url: str,
    item: dict[str, object],
    output_dir: Path,
    operator: str,
    resume_task_id: str | None = None,
    resume_task_no: str | None = None,
    resume_file_id: str | None = None,
) -> dict[str, object]:
    company = str(item["company_name"])
    credit_code = str(item["credit_code"])
    baseline = Path(str(item["path"]))
    if resume_task_id:
        if not resume_task_no or not resume_file_id:
            raise ValueError(
                "resume_task_no and resume_file_id are required with resume_task_id"
            )
        task_id = resume_task_id
        task_no = resume_task_no
        file_id = resume_file_id
        print(f"[{company}] 1-2/8 续跑既有任务 {task_no}", flush=True)
    else:
        print(f"[{company}] 1/8 上传技术试跑旧报告", flush=True)
        upload = upload_docx(base_url, baseline)
        file_id = str(upload["file_id"])

        print(f"[{company}] 2/8 创建任务", flush=True)
        created = request_json(
            base_url,
            "POST",
            "/api/tasks",
            body={
                "prompt": f"更新{company}企业风险监测分析报告（ES技术试跑，非正式报告）",
                "company_query": credit_code,
                "previous_report_file_id": file_id,
            },
            headers={
                "Idempotency-Key": "es-tech-trial-" + uuid.uuid4().hex,
                "X-Operator-Id": operator,
            },
        )
        task_id = str(created["task_id"])
        task_no = str(created["task_no"])

    print(f"[{company}] 3/8 执行ES采集与Tavily检索", flush=True)
    executed: object = {}
    status = ""
    for _ in range(4):
        executed = request_json(
            base_url,
            "POST",
            f"/api/tasks/{task_id}/execute",
            headers={"X-Worker-Id": "es-tech-trial-worker"},
            timeout=300,
        )
        status = str(executed["status"])
        if status == "CALCULATING_RISK":
            break
        if status not in {
            "RESOLVING_SUBJECT",
            "LOADING_PREVIOUS_REPORT",
            "COLLECTING_STRUCTURED_DATA",
            "SEARCHING_PUBLIC_INTELLIGENCE",
        }:
            break
    if status != "CALCULATING_RISK":
        raise ApiError(f"{task_no} stopped at unexpected status {status}: {executed}")

    evidence = request_json(
        base_url, "GET", f"/api/tasks/{task_id}/public-intelligence/evidence"
    )
    print(f"[{company}] 4/8 排除 {len(evidence)} 条未研判公开信息", flush=True)
    for evidence_item in evidence:
        request_json(
            base_url,
            "POST",
            f"/api/tasks/{task_id}/public-intelligence/evidence/"
            f"{evidence_item['evidence_id']}/decision",
            body={"decision": "REJECTED", "reason": REJECTION_REASON},
            headers={"X-Operator-Id": operator},
        )

    snapshot = request_json(base_url, "GET", f"/api/tasks/{task_id}/snapshot")
    company_fields = snapshot.get("company_facts", {}).get("additional_fields", {})
    completeness = str(
        company_fields.get("legacyFeatureCompleteness")
        or company_fields.get("legacyFeaturesComplete")
        or ""
    ).strip()
    legacy_features_complete = completeness.upper() == "FULL" or completeness.lower() in {
        "true",
        "1",
    }

    print(f"[{company}] 5/8 按冻结ES快照计算确定性评分", flush=True)
    score = request_json(
        base_url,
        "POST",
        f"/api/tasks/{task_id}/risk-score/calculate-from-confirmed-evidence",
    )

    print(f"[{company}] 6/8 记录技术试跑确认", flush=True)
    confirmation = request_json(
        base_url,
        "POST",
        f"/api/tasks/{task_id}/operator-confirmation",
        body={
            "note": "ES技术试跑：仅确认数据和报告链路；未做LLM或人工舆情语义研判，不构成正式风险结论"
        },
        headers={"X-Operator-Id": operator},
    )

    print(f"[{company}] 7/8 生成并下载非正式DOCX", flush=True)
    report = request_json(
        base_url,
        "POST",
        f"/api/tasks/{task_id}/reports",
        headers={"X-Operator-Id": operator},
        timeout=300,
    )
    if report.get("status") != "GENERATED":
        raise ApiError(f"{task_no} report generation failed: {report}")
    report_id = str(report["report_id"])
    docx, response_headers, _ = request(
        base_url,
        "GET",
        f"/api/tasks/{task_id}/reports/{report_id}/download",
        timeout=300,
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{company}_企业风险监测分析报告_ES技术试跑_非正式_{task_no}.docx"
    output_path.write_bytes(docx)

    print(f"[{company}] 8/8 执行DOCX结构与身份残留检查", flush=True)
    xml = visible_docx_text(output_path)
    content_hash = sha256_bytes(docx)
    response_hash = response_headers.get("X-Content-SHA256") or response_headers.get(
        "X-content-sha256"
    )
    stale = [marker for marker in STALE_REFERENCE_MARKERS if marker in xml]
    checks = {
        "docx_package_valid": True,
        "company_name_present": company in xml,
        "credit_code_present": credit_code in xml,
        "response_hash_matches": response_hash is None or response_hash == content_hash,
        "stale_reference_markers": stale,
        "structural_pass": (
            company in xml
            and credit_code in xml
            and not stale
            and (response_hash is None or response_hash == content_hash)
        ),
        "visual_render_pass": False,
        "visual_render_note": "本机缺少LibreOffice，未执行PNG渲染；需Word复核",
    }
    scoring_context = {
        "migration_mode": (
            "COMPLETE_MIGRATED_RULE_RECALCULATION"
            if legacy_features_complete
            else "MATERIALIZED_LEGACY_SCORE_FALLBACK"
        ),
        "legacy_features_complete": legacy_features_complete,
        "materialized_legacy_score": company_fields.get("legacyScore"),
        "business_score_validated": False,
        "business_score_validation_note": (
            "技术试跑没有历史人工终稿期望值，不能把链路计算结果视为业务评分验收。"
        ),
    }
    acceptance = {
        "engineering_chain_pass": checks["structural_pass"],
        "business_score_validated": False,
        "visual_layout_validated": False,
        "overall": (
            "ENGINEERING_PASS_BUSINESS_PENDING"
            if checks["structural_pass"]
            else "ENGINEERING_FAILED"
        ),
    }
    result = {
        "purpose": "ES技术试跑，不属于黄金样本或正式风险结论",
        "company_name": company,
        "credit_code": credit_code,
        "risk_event_count": item["risk_event_count"],
        "baseline_file": str(baseline.resolve()),
        "uploaded_file_id": file_id,
        "task_id": task_id,
        "task_no": task_no,
        "task_status_before_report": status,
        "evidence_count": len(evidence),
        "evidence_policy": "all rejected because semantic review was not performed",
        "score": score,
        "scoring_context": scoring_context,
        "operator_confirmation": confirmation,
        "report_id": report_id,
        "report_status": report["status"],
        "report_version_no": report.get("report_version_no"),
        "template_version": report.get("template_version"),
        "server_content_hash": report.get("content_hash"),
        "download_sha256": content_hash,
        "download_size_bytes": len(docx),
        "output_file": str(output_path.resolve()),
        "checks": checks,
        "acceptance": acceptance,
    }
    result_path = output_path.with_suffix(".json")
    result_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"[{company}] 完成：{task_no}；结构检查="
        f"{'通过' if checks['structural_pass'] else '失败'}",
        flush=True,
    )
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://10.210.0.62:8301")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--operator", default="es-tech-trial")
    parser.add_argument("--start-index", type=int, default=0)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--resume-task-id")
    parser.add_argument("--resume-task-no")
    parser.add_argument("--resume-file-id")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    companies = manifest["companies"][args.start_index :]
    if args.limit is not None:
        companies = companies[: args.limit]
    if not companies:
        raise SystemExit("No companies selected")

    results: list[dict[str, object]] = []
    batch_path = args.output_dir / "ES技术试跑批次结果.json"
    try:
        for index, item in enumerate(companies):
            results.append(
                run_company(
                    args.base_url,
                    item,
                    args.output_dir,
                    args.operator,
                    args.resume_task_id if index == 0 else None,
                    args.resume_task_no if index == 0 else None,
                    args.resume_file_id if index == 0 else None,
                )
            )
            batch_path.parent.mkdir(parents=True, exist_ok=True)
            batch_path.write_text(
                json.dumps(results, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
    except Exception as error:
        failure = {
            "completed": results,
            "failed_after_completed_count": len(results),
            "error": str(error),
        }
        batch_path.parent.mkdir(parents=True, exist_ok=True)
        batch_path.write_text(
            json.dumps(failure, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"批次停止：{error}", file=sys.stderr, flush=True)
        raise


if __name__ == "__main__":
    main()
