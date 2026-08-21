#!/usr/bin/env python3
"""Select a reviewable golden-sample candidate pool from the current ES seed data.

The output is deliberately a *candidate* pool.  It never marks Atlas-produced
scores or labels as business truth; operations must confirm the expected result
before a case can be promoted to the formal golden set.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path
from typing import Any, Iterable


EVENT_COUNTS = {
    "北京城建亚泰建设集团有限公司": 2317,
    "东方炫辰（北京）科技发展有限公司": 639,
    "北京全时叁陆伍连锁便利店有限公司": 526,
    "东兆长泰集团有限公司": 411,
    "北京达毅思创公关顾问有限公司": 268,
    "北京腾信创新网络营销技术股份有限公司": 129,
    "北京互联港湾科技有限公司": 72,
}

PUBLIC_INTEL_COUNTS = {
    "北京城建亚泰建设集团有限公司": 205,
    "东方炫辰（北京）科技发展有限公司": 10,
    "北京互联港湾科技有限公司": 5,
    "北京达毅思创公关顾问有限公司": 2,
    "东兆长泰集团有限公司": 1,
    "北京全时叁陆伍连锁便利店有限公司": 1,
    "北京腾信创新网络营销技术股份有限公司": 1,
}

TRIAL_PRIORITY = [
    "北京城建亚泰建设集团有限公司",
    "乐投财富（北京）投资管理有限公司",
    "北京腾信创新网络营销技术股份有限公司",
    "北京宠颐生宠物店（个体工商户）",
    "东方炫辰（北京）科技发展有限公司",
    "东兆长泰集团有限公司",
    "北京达毅思创公关顾问有限公司",
    "乾道投资控股集团有限公司",
    "北京童程童慧科技有限公司",
    "北京拾光萌宠宠物用品店（个体工商户）",
    "北京三泓纪餐饮店（个体工商户）",
    "北京膳和兴业餐饮中心（个体工商户）",
    "北京简熹和食品有限公司",
    "北京全时叁陆伍连锁便利店有限公司",
    "北京互联港湾科技有限公司",
]

NORMAL_STATUSES = {"存续", "在业", "开业", "正常", "迁入"}


def read_bulk_documents(path: Path) -> list[dict[str, Any]]:
    documents: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        iterator = iter(stream)
        for metadata_line in iterator:
            if not metadata_line.strip():
                continue
            metadata = json.loads(metadata_line)
            if "index" not in metadata and "create" not in metadata:
                raise ValueError(f"Unexpected bulk metadata in {path}: {metadata}")
            try:
                source_line = next(iterator)
            except StopIteration as error:
                raise ValueError(f"Missing source line after bulk metadata in {path}") from error
            documents.append(json.loads(source_line))
    return documents


def industry_label(company: dict[str, Any]) -> str:
    value = (company.get("industry") or {}).get("source_value")
    if not value:
        return "/"
    try:
        parsed = json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return str(value)
    return (
        parsed.get("国标行业大类")
        or parsed.get("国标行业门类")
        or parsed.get("国标行业中类")
        or "/"
    )


def build_counts(documents: Iterable[dict[str, Any]]) -> Counter[str]:
    return Counter(str(item.get("atlas_company_id") or "") for item in documents)


def risk_value(company: dict[str, Any]) -> float:
    value = (company.get("risk_projection") or {}).get("legacy_score")
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def classify_roles(row: dict[str, Any]) -> list[str]:
    roles: list[str] = []
    if row["event_count"]:
        roles.append("结构化风险覆盖")
    if row["public_intel_count"]:
        roles.append("存量公开信息覆盖")
    if row["status"] not in NORMAL_STATUSES:
        roles.append("工商状态边界")
    if row["legacy_score"] > 0 or row["legacy_labels"]:
        roles.append("旧模型兼容")
    if row["asset_count"] or row["relation_count"] or row["contact_count"]:
        roles.append("多维数据完整性")
    if "个体工商户" in row["company_type"] or "个体工商户" in row["company_name"]:
        roles.append("低信息量小微主体")
    if not roles:
        roles.append("无已知风险对照")
    return roles


def rank_value(row: dict[str, Any]) -> float:
    abnormal_status = row["status"] not in NORMAL_STATUSES
    return (
        (100_000 if row["event_count"] else 0)
        + math.log1p(row["event_count"]) * 1_000
        + math.log1p(row["public_intel_count"]) * 500
        + (10_000 if abnormal_status else 0)
        + (5_000 if row["legacy_labels"] else 0)
        + min(row["legacy_score"], 10) * 200
        + math.log1p(row["asset_count"]) * 100
        + math.log1p(row["relation_count"]) * 80
        + math.log1p(row["contact_count"]) * 60
    )


def select_rows(rows: list[dict[str, Any]], target: int) -> list[dict[str, Any]]:
    by_name = {row["company_name"]: row for row in rows}
    selected: list[dict[str, Any]] = []
    selected_ids: set[str] = set()

    def add(row: dict[str, Any] | None) -> None:
        if row and row["atlas_company_id"] not in selected_ids and len(selected) < target:
            selected.append(row)
            selected_ids.add(row["atlas_company_id"])

    # Preserve the real 12-company trial and its three established references.
    for name in TRIAL_PRIORITY:
        add(by_name.get(name))

    # Add every remaining status/legacy boundary before ordinary clean controls.
    boundaries = sorted(
        (
            row
            for row in rows
            if row["status"] not in NORMAL_STATUSES
            or row["legacy_score"] > 0
            or row["legacy_labels"]
        ),
        key=rank_value,
        reverse=True,
    )
    for row in boundaries:
        add(row)

    # Ensure data-rich but non-risk businesses are represented.
    rich = sorted(
        rows,
        key=lambda row: (
            row["asset_count"] + row["relation_count"] + row["contact_count"],
            rank_value(row),
        ),
        reverse=True,
    )
    for row in rich[:12]:
        add(row)

    # Fill with clean controls while rotating industries and entity types.
    clean = [
        row
        for row in rows
        if not row["event_count"]
        and not row["public_intel_count"]
        and row["legacy_score"] == 0
        and not row["legacy_labels"]
        and row["status"] in NORMAL_STATUSES
    ]
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in clean:
        entity_kind = "个体工商户" if "个体工商户" in row["company_type"] else "企业"
        groups[(entity_kind, row["industry"])].append(row)
    for group in groups.values():
        group.sort(
            key=lambda row: (
                row["asset_count"] + row["relation_count"] + row["contact_count"],
                row["company_name"],
            ),
            reverse=True,
        )
    while len(selected) < target and groups:
        progressed = False
        for key in sorted(groups):
            if groups[key]:
                add(groups[key].pop(0))
                progressed = True
                if len(selected) >= target:
                    break
        groups = {key: group for key, group in groups.items() if group}
        if not progressed:
            break

    for row in sorted(rows, key=rank_value, reverse=True):
        add(row)
    return selected


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--target", type=int, default=30)
    args = parser.parse_args()
    if not 20 <= args.target <= 50:
        raise SystemExit("--target must be between 20 and 50")

    companies = read_bulk_documents(args.input_dir / "company.ndjson")
    relation_counts = build_counts(read_bulk_documents(args.input_dir / "company_relation.ndjson"))
    contact_counts = build_counts(read_bulk_documents(args.input_dir / "company_contact.ndjson"))
    asset_counts = build_counts(read_bulk_documents(args.input_dir / "company_asset.ndjson"))

    rows: list[dict[str, Any]] = []
    for company in companies:
        company_id = str(company["atlas_company_id"])
        name = str((company.get("name") or {}).get("canonical") or "")
        registration = company.get("registration") or {}
        projection = company.get("risk_projection") or {}
        row = {
            "atlas_company_id": company_id,
            "company_name": name,
            "credit_code": str((company.get("identity") or {}).get("credit_code") or "/"),
            "status": str(registration.get("status") or "/"),
            "company_type": str(registration.get("company_type") or "/"),
            "industry": industry_label(company),
            "legacy_score": risk_value(company),
            "legacy_labels": list(projection.get("legacy_labels") or []),
            "event_count": EVENT_COUNTS.get(name, 0),
            "public_intel_count": PUBLIC_INTEL_COUNTS.get(name, 0),
            "relation_count": relation_counts[company_id],
            "contact_count": contact_counts[company_id],
            "asset_count": asset_counts[company_id],
            "source_updated_at": str((company.get("source") or {}).get("source_updated_at") or "/"),
            "snapshot": company,
        }
        row["roles"] = classify_roles(row)
        rows.append(row)

    selected = select_rows(rows, args.target)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case_dir = args.output_dir / "cases"
    case_dir.mkdir(parents=True, exist_ok=True)

    manifest_cases: list[dict[str, Any]] = []
    for sequence, row in enumerate(selected, 1):
        case_id = f"GOLD-CAND-{sequence:03d}"
        baseline_path = case_dir / f"{case_id}.json"
        baseline = {
            "schema_version": "atlas-golden-candidate.v1",
            "candidate_id": case_id,
            "selected_at": date.today().isoformat(),
            "business_confirmed": False,
            "selection_roles": row["roles"],
            "observed_counts": {
                "structured_risk_events": row["event_count"],
                "stored_public_intel": row["public_intel_count"],
                "relations": row["relation_count"],
                "contacts": row["contact_count"],
                "assets": row["asset_count"],
            },
            "legacy_projection": {
                "score": row["legacy_score"],
                "labels": row["legacy_labels"],
                "note": "仅作历史迁移参照，不作为当前业务期望真值。",
            },
            "expected_result": {
                "status": "PENDING_BUSINESS_REVIEW",
                "risk_score": None,
                "risk_level": None,
                "risk_labels": [],
                "required_evidence": [],
            },
            "company_snapshot": row["snapshot"],
        }
        baseline_path.write_text(
            json.dumps(baseline, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        manifest_cases.append(
            {
                "candidate_id": case_id,
                "company_name": row["company_name"],
                "credit_code": row["credit_code"],
                "selection_roles": row["roles"],
                "business_confirmed": False,
                "baseline_path": baseline_path.relative_to(args.output_dir).as_posix(),
            }
        )

    manifest = {
        "schema_version": "atlas-golden-candidate-pool.v1",
        "selected_at": date.today().isoformat(),
        "source": "work/es-dev-100 离线快照；事件与公开信息数量已与 10.210.0.62 ES 核对",
        "target_count": args.target,
        "actual_count": len(manifest_cases),
        "promotion_rule": "只有补齐业务期望结果并签字后，才可迁入正式黄金样本集。",
        "cases": manifest_cases,
    }
    (args.output_dir / "candidate-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    with (args.output_dir / "business-confirmation.csv").open(
        "w", encoding="utf-8-sig", newline=""
    ) as stream:
        fields = [
            "候选编号", "企业名称", "统一社会信用代码", "样本角色", "工商状态",
            "结构化风险数", "存量公开信息数", "关系数", "联系信息数", "资产数",
            "历史分_仅参照", "历史标签_仅参照", "业务期望风险分", "业务期望风险等级",
            "业务期望风险标签", "必须引用的证据", "业务确认人", "确认日期", "确认结论", "备注",
        ]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for case, row in zip(manifest_cases, selected):
            writer.writerow({
                "候选编号": case["candidate_id"],
                "企业名称": row["company_name"],
                "统一社会信用代码": row["credit_code"],
                "样本角色": "；".join(row["roles"]),
                "工商状态": row["status"],
                "结构化风险数": row["event_count"],
                "存量公开信息数": row["public_intel_count"],
                "关系数": row["relation_count"],
                "联系信息数": row["contact_count"],
                "资产数": row["asset_count"],
                "历史分_仅参照": row["legacy_score"],
                "历史标签_仅参照": "；".join(map(str, row["legacy_labels"])),
                "业务期望风险分": "",
                "业务期望风险等级": "",
                "业务期望风险标签": "",
                "必须引用的证据": "",
                "业务确认人": "",
                "确认日期": "",
                "确认结论": "待确认",
                "备注": "",
            })

    role_counts = Counter(role for row in selected for role in row["roles"])
    status_counts = Counter(row["status"] for row in selected)
    summary = {
        "selected_count": len(selected),
        "role_counts": dict(role_counts),
        "status_counts": dict(status_counts),
        "companies": [
            {
                key: value
                for key, value in row.items()
                if key != "snapshot"
            }
            for row in selected
        ],
    }
    (args.output_dir / "selection-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
