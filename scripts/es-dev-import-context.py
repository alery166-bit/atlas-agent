#!/usr/bin/env python3
"""Install contact/relation/asset templates and import the generated context sample."""

from __future__ import annotations

import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path


TASK_ROOT = Path(__file__).resolve().parents[1]


def installer_module():
    path = Path(__file__).with_name("es-dev-install.py")
    spec = importlib.util.spec_from_file_location("atlas_es_dev_install", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Could not load es-dev-install.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:9200")
    args = parser.parse_args()
    module = installer_module()
    installed = module.install_templates(args.url)
    module.request(
        args.url,
        "/atlas-company-contact-v1-000001/_mapping",
        "PUT",
        {"properties": {"ingest": {"properties": {
            "dedupe_key": {"type": "keyword"}
        }}}},
    )
    sample = TASK_ROOT / "work" / "es-dev-100"
    imported = {
        name: module.import_bulk(
            args.url,
            name,
            sample / f"{name}.ndjson",
            sample,
        )
        for name in ("company_contact", "company_relation", "company_asset")
    }
    target_company_id = "d7913744-9239-58c7-8da5-7408739bf48d"
    query = {
        "size": 100,
        "query": {"term": {"atlas_company_id": target_company_id}},
    }
    target_relations = module.request(
        args.url,
        "/atlas-company-relation-read/_search?routing=" + target_company_id,
        "POST",
        query,
    )["hits"]["hits"]
    target_contacts = module.request(
        args.url,
        "/atlas-company-contact-read/_search?routing=" + target_company_id,
        "POST",
        query,
    )["hits"]["hits"]
    target_assets = module.request(
        args.url,
        "/atlas-company-asset-read/_search?routing=" + target_company_id,
        "POST",
        {
            "size": 10000,
            "query": {"term": {"atlas_company_id": target_company_id}},
        },
    )["hits"]["hits"]
    result = {
        "templates": installed,
        "imported": imported,
        "actual_counts": {
            "atlas-company-contact-read": module.count(
                args.url, "atlas-company-contact-read"
            ),
            "atlas-company-relation-read": module.count(
                args.url, "atlas-company-relation-read"
            ),
            "atlas-company-asset-read": module.count(
                args.url, "atlas-company-asset-read"
            ),
        },
        "beijing_internet_harbour": {
            "relation_counts": dict(Counter(
                hit["_source"]["relation_type"] for hit in target_relations
            )),
            "websites": sorted({
                hit["_source"].get("value")
                for hit in target_contacts
                if hit["_source"].get("contact_type") == "WEBSITE"
                and hit["_source"].get("value")
            }),
            "asset_counts": dict(Counter(
                hit["_source"]["asset_type"] for hit in target_assets
            )),
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
