#!/usr/bin/env python3
"""Inventory historical DOCX files without inventing golden-sample labels."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree


COMPANY_DATE = re.compile(
    r"^(?P<company>.+?)_企业风险监测分析报告(?P<date>\d{8})\.docx$"
)
CREDIT_CODE = re.compile(r"(?<![0-9A-Z])[0-9A-Z]{18}(?![0-9A-Z])")
SCORE_PATTERNS = (
    re.compile(r"(?:人工分|人工评分|调整后(?:风险)?分)\s*[：:]?\s*(10(?:\.0+)?|[0-9](?:\.\d+)?)"),
    re.compile(r"(?:原始分|模型分|系统分|风险评分)\s*[：:]?\s*(10(?:\.0+)?|[0-9](?:\.\d+)?)"),
)
RISK_LEVELS = ("高风险", "中高风险", "中风险", "中低风险", "低风险")


def docx_text(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        root = ElementTree.fromstring(archive.read("word/document.xml"))
    values = [node.text or "" for node in root.iter() if node.tag.endswith("}t")]
    return "\n".join(value.strip() for value in values if value.strip())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inventory(path: Path) -> dict[str, object]:
    match = COMPANY_DATE.match(path.name)
    text = docx_text(path)
    codes = sorted(set(CREDIT_CODE.findall(text)))
    scores = []
    for pattern in SCORE_PATTERNS:
        found = pattern.search(text)
        scores.append(found.group(1) if found else None)
    levels = [level for level in RISK_LEVELS if level in text]
    return {
        "filename": path.name,
        "company_name": match.group("company") if match else None,
        "report_date": match.group("date") if match else None,
        "unified_credit_codes": codes,
        "detected_original_score": scores[1],
        "detected_manual_score": scores[0],
        "detected_risk_levels": levels,
        "size_bytes": path.stat().st_size,
        "sha256": sha256(path),
        "intake_status": "REFERENCE_ONLY",
        "missing_for_formal_golden": [
            "paired_original_material",
            "business_confirmed_final_report",
            "company_json_snapshot",
            "operator_decisions",
            "confirmed_expected_score_and_labels",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=Path("data/historical-reports/incoming"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("work/historical-reports/golden-intake-inventory.json"),
    )
    args = parser.parse_args()

    reports = [inventory(path) for path in sorted(args.input_dir.glob("*.docx"))]
    payload = {
        "schema_version": "atlas-golden-intake.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "input_dir": args.input_dir.as_posix(),
        "report_count": len(reports),
        "formal_golden_ready_count": 0,
        "required_formal_case_count": 20,
        "status": "MATERIALS_INCOMPLETE",
        "note": (
            "当前文件可用于模板与历史报告参考，但不能推断为业务签字终稿，"
            "也不能据此虚构分数、标签或证据取舍。"
        ),
        "reports": reports,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "report_count": len(reports),
        "formal_golden_ready_count": 0,
        "status": payload["status"],
        "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
