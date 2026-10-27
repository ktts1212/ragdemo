#!/usr/bin/env python3
# Step 5 task 4 - freeze evaluation set v4 (adds verified KB mapping per question).
#  * keeps every question and reference answer byte-identical (no gold changes);
#  * fills document_id / knowledge_base_id for questions whose paper is VERIFIED in the eval KB;
#  * preserves the V3 tier split (core = SEMANTIC_VERIFIED + VISUAL_PENDING) and the review queue;
#  * writes a document manifest (paper_id -> document_id, sha256, version, status, chunks) with its own hash;
#  * never modifies frozen V1/V2/V3 artifacts.

import hashlib
import json
import time
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DS = REPO / "eval-dataset"
FR = DS / "frozen"


def read_jsonl(p):
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] if p.exists() else []


def write_jsonl(p, rows):
    tmp = p.with_suffix(p.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    tmp.replace(p)


def sha256_file(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    rows = read_jsonl(FR / "candidates.v3.jsonl")
    align = json.loads((DS / "kb_alignment.json").read_text(encoding="utf-8"))
    per_paper = {}
    manifest = []
    for p in align["papers"]:
        cand = p["candidates"][0] if p.get("candidates") else None
        per_paper[p["paper_id"]] = p
        if cand:
            manifest.append({
                "paper_id": p["paper_id"], "pdf_sha256": p["pdf_sha256"],
                "document_id": cand["document_id"], "knowledge_base_id": cand["knowledge_base_id"],
                "version": cand.get("document_version"), "status": cand.get("document_status"),
                "chunks": cand.get("chunks"), "match_method": p.get("match_method"),
                "mapping_status": p.get("status"),
            })

    mapped = 0
    out = []
    for row in rows:
        paper = per_paper.get(row.get("paper_id"), {})
        cand = paper.get("candidates")[0] if paper.get("candidates") else None
        if paper.get("status") == "VERIFIED" and cand:
            row["document_id"] = cand["document_id"]
            row["knowledge_base_id"] = cand["knowledge_base_id"]
            row["document_version"] = cand.get("document_version")
            row["document_status"] = cand.get("document_status")
            row["kb_mapping_status"] = "VERIFIED"
            mapped += 1
        else:
            row["document_id"] = None
            row["knowledge_base_id"] = None
            row["kb_mapping_status"] = paper.get("status", "UNKNOWN")
        row["dataset_version"] = "v4"
        out.append(row)

    core = [r for r in out if r.get("quality_tier") in ("SEMANTIC_VERIFIED", "VISUAL_PENDING")]
    semantic = [r for r in core if r.get("quality_tier") == "SEMANTIC_VERIFIED"]
    visual = [r for r in core if r.get("quality_tier") == "VISUAL_PENDING"]
    review = [r for r in out if r.get("quality_tier") == "REVIEW_REQUIRED"]

    write_jsonl(FR / "candidates.v4.jsonl", out)
    write_jsonl(FR / "core_subset.v4.jsonl", core)
    write_jsonl(FR / "review_queue.v4.jsonl", review)
    write_jsonl(FR / "visual_todo.v4.jsonl", visual)
    (FR / "document_manifest.v4.json").write_text(json.dumps({
        "knowledge_base_id": align.get("knowledge_base_id", 2),
        "documents": manifest,
        "sha256_of_this_manifest": None,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    man_path = FR / "document_manifest.v4.json"
    man = json.loads(man_path.read_text(encoding="utf-8"))
    man["sha256_of_this_manifest"] = sha256_file(man_path)
    man_path.write_text(json.dumps(man, ensure_ascii=False, indent=2), encoding="utf-8")

    artifacts = ["candidates.v4.jsonl", "core_subset.v4.jsonl", "review_queue.v4.jsonl",
                 "visual_todo.v4.jsonl", "document_manifest.v4.json"]
    freeze = {
        "schema_version": "1.0",
        "dataset_version": "v4",
        "frozen_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "counts": {"candidates": len(out), "core": len(core), "semantic_verified": len(semantic),
                   "visual_pending": len(visual), "review_required": len(review)},
        "by_category_core": dict(Counter(r.get("question_type") for r in core)),
        "kb": {"knowledge_base_id": align.get("knowledge_base_id", 2),
               "verified_papers": align["summary"]["VERIFIED"],
               "ambiguous": align["summary"]["AMBIGUOUS"], "not_found": align["summary"]["NOT_FOUND"],
               "questions_with_document_id": mapped},
        "rules": {
            "gold": "reference answers were never modified (byte-identical to V3)",
            "core": "SEMANTIC_VERIFIED (91) + VISUAL_PENDING (19); review queue excluded",
            "mapping": "document_id filled only when kb mapping status == VERIFIED (no guessing)",
        },
        "artifacts_sha256": {a: sha256_file(FR / a) for a in artifacts},
        "supersedes": "v3 (kept on disk, unmodified)",
    }
    (FR / "FREEZE.v4.json").write_text(json.dumps(freeze, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[v4] candidates={len(out)} core={len(core)} (semantic={len(semantic)} visual={len(visual)}) "
          f"review={len(review)} mapped_questions={mapped}")
    print(f"[v4] kb={freeze['kb']}")
    for a, h in freeze["artifacts_sha256"].items():
        print(f"[v4] {a} sha256={h[:16]}...")


if __name__ == "__main__":
    main()
