#!/usr/bin/env python3
"""Create isolated ES technical-trial prior reports from the retained V1 DOCX.

The script performs one narrow OOXML edit: it replaces the retained sample
company name in ``word/document.xml``. Every other package part is copied with
identical uncompressed bytes and verified after writing.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


SOURCE_COMPANY = "北京简熹和食品有限公司"
COMPANIES = [
    {
        "company_name": "北京互联港湾科技有限公司",
        "credit_code": "91110105690017431R",
        "risk_event_count": 72,
    },
    {
        "company_name": "北京腾信创新网络营销技术股份有限公司",
        "credit_code": "91110000801169113H",
        "risk_event_count": 129,
    },
    {
        "company_name": "北京达毅思创公关顾问有限公司",
        "credit_code": "91110105693260293N",
        "risk_event_count": 268,
    },
    {
        "company_name": "东兆长泰集团有限公司",
        "credit_code": "91110000784802051J",
        "risk_event_count": 411,
    },
    {
        "company_name": "北京全时叁陆伍连锁便利店有限公司",
        "credit_code": "9111010558589351XB",
        "risk_event_count": 526,
    },
]


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def package_parts(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path, "r") as archive:
        return {
            name: archive.read(name)
            for name in archive.namelist()
            if not name.endswith("/")
        }


def build_one(reference: Path, output: Path, company_name: str) -> dict[str, object]:
    baseline = package_parts(reference)
    document_part = "word/document.xml"
    original_xml = baseline[document_part]
    source_bytes = SOURCE_COMPANY.encode("utf-8")
    target_bytes = company_name.encode("utf-8")
    replacements = original_xml.count(source_bytes)
    if replacements < 1:
        raise RuntimeError(f"No source company slot found in {document_part}")
    changed_xml = original_xml.replace(source_bytes, target_bytes)

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        with zipfile.ZipFile(reference, "r") as source:
            for info in source.infolist():
                if info.is_dir():
                    archive.writestr(info, b"")
                    continue
                content = baseline[info.filename]
                if info.filename == document_part:
                    content = changed_xml
                archive.writestr(info, content)

    generated = package_parts(output)
    if set(generated) != set(baseline):
        raise RuntimeError(f"Package part set changed for {output.name}")
    preservation_failures = [
        name
        for name, content in baseline.items()
        if name != document_part and generated[name] != content
    ]
    if preservation_failures:
        raise RuntimeError(
            f"Preserve-only parts changed for {output.name}: {preservation_failures}"
        )
    if generated[document_part].count(target_bytes) != replacements:
        raise RuntimeError(f"Target company replacement count is incorrect for {output.name}")
    if source_bytes in generated[document_part]:
        raise RuntimeError(f"Source company remains in {output.name}")

    return {
        "filename": output.name,
        "path": str(output.resolve()),
        "sha256": sha256_file(output),
        "size_bytes": output.stat().st_size,
        "document_xml_replacements": replacements,
        "package_part_count": len(generated),
        "preserve_only_parts_verified": len(generated) - 1,
        "preserve_only_failures": preservation_failures,
        "document_xml_before_sha256": sha256_bytes(original_xml),
        "document_xml_after_sha256": sha256_bytes(changed_xml),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()

    reference = args.reference.resolve()
    if not reference.is_file():
        raise FileNotFoundError(reference)

    manifest: dict[str, object] = {
        "purpose": "ES技术试跑旧报告基线，不属于黄金样本或正式业务报告",
        "reference_path": str(reference),
        "reference_sha256": sha256_file(reference),
        "source_company": SOURCE_COMPANY,
        "companies": [],
    }
    for item in COMPANIES:
        name = item["company_name"]
        output = args.output_dir / f"{name}_ES技术试跑基准旧报告_非正式.docx"
        result = build_one(reference, output, name)
        manifest["companies"].append({**item, **result})

    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
