#!/usr/bin/env python3
"""Generate an incremental ES company bulk file for explicitly named companies."""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
from pathlib import Path


TASK_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = TASK_ROOT / "data" / "company" / "company_base.csv"
DEFAULT_OUTPUT = TASK_ROOT / "work" / "es-dev-selected" / "company.ndjson"
GENERATOR_PATH = Path(__file__).with_name("es-dev-generate-sample.py")
INDEX_NAME = "atlas-company-v1-000001"


def load_generator():
    spec = importlib.util.spec_from_file_location("atlas_es_sample_generator", GENERATOR_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load {GENERATOR_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--company-name", action="append", required=True)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--batch-id", default="es-dev-selected-20260811")
    parser.add_argument("--ingested-at", default="2026-08-11T00:00:00+08:00")
    args = parser.parse_args()

    requested = list(dict.fromkeys(name.strip() for name in args.company_name if name.strip()))
    if not requested:
        raise ValueError("At least one non-blank company name is required")

    generator = load_generator()
    found: dict[str, dict[str, object]] = {}
    with args.source.open("r", encoding="utf-8-sig", newline="") as source_file:
        for row in csv.DictReader(source_file):
            name = (row.get("company_name") or "").strip()
            if name in requested:
                found[name] = generator.company_document(row, args.batch_id, args.ingested_at)
                if len(found) == len(requested):
                    break

    missing = [name for name in requested if name not in found]
    if missing:
        raise RuntimeError(f"Companies not found in company_base.csv: {missing}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output_file:
        for name in requested:
            document = found[name]
            action = {
                "index": {
                    "_index": INDEX_NAME,
                    "_id": document["atlas_company_id"],
                }
            }
            output_file.write(json.dumps(action, ensure_ascii=False, separators=(",", ":")) + "\n")
            output_file.write(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n")

    manifest = {
        "batch_id": args.batch_id,
        "index": INDEX_NAME,
        "company_count": len(requested),
        "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
        "companies": [
            {
                "company_name": name,
                "atlas_company_id": found[name]["atlas_company_id"],
                "source_company_id": found[name]["identity"]["source_company_id"],
                "credit_code": found[name]["identity"].get("credit_code"),
            }
            for name in requested
        ],
    }
    manifest_path = args.output.with_name("manifest.json")
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
