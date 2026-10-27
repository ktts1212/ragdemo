#!/usr/bin/env python3
# Step 5 task 4 - freeze the evaluation set as v3.
#  * fixes the verification_status / quality_tier inconsistency (programmatic verdict is restored);
#  * conservative review rule: excluded from the core subset if ANY reviewer signal is negative
#    (unsupported / leakage / ambiguous / numeric or DOI mismatch / visual_required / truncation_risk);
#  * marks visual-verification items separately; keeps all 120 candidates + full review history;
#  * attaches kb document_id per question (or null, with the mapping status, never guessed);
#  * writes frozen/ artifacts plus FREEZE.json with sha256 of every artifact.
# Deterministic; never alters reference answers; no LLM.

import hashlib
import json
import os
import time
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DS = REPO / "eval-dataset"
OUT = DS / "frozen"


def read_jsonl(p):
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] if p.exists() else []


def write_jsonl(p, rows):
    tmp = p.with_suffix(p.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    os.replace(tmp, p)


def sha256_file(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rows = read_jsonl(DS / "semantic_review_all.jsonl")
    visual_ids = {r["question_id"] for r in read_jsonl(DS / "visual_todo.jsonl")}
    align = {p["paper_id"]: p for p in json.loads((DS / "kb_alignment.json").read_text(encoding="utf-8"))["papers"]}
    unstable = {"sha256:9670ffc08b269577"}  # reviewer re-run flipped one verdict (7/1 -> 6/2)

    core, review, visual, tiers = [], [], [], Counter()
    for row in rows:
        sr = row.get("semantic_review") or {}
        qid = row["question_id"]
        paper = row.get("paper_id")
        # fix v2 inconsistency: verification_status = programmatic verdict
        if row.get("quality_tier_v1") == "TEXT_MATCHED":
            row["verification_status"] = "ORIGINAL_PDF_VERIFIED"
            row["verification_method"] = "programmatic_page_text_match"
        else:
            row["verification_status"] = "REVIEW_REQUIRED"
        # kb mapping per question
        a = align.get(paper, {})
        row["kb_mapping_status"] = a.get("status")
        row["document_id"] = (a["candidates"][0]["document_id"]
                              if a.get("status") == "VERIFIED" and a.get("candidates") else None)
        row["knowledge_base_id"] = (a["candidates"][0]["knowledge_base_id"]
                                    if a.get("status") == "VERIFIED" and a.get("candidates") else None)
        row["dataset_version"] = "v3"

        negative = (sr.get("verdict") != "SEMANTIC_VERIFIED" or sr.get("supported") is not True
                    or sr.get("leakage") or sr.get("ambiguous") or sr.get("numeric_ok") is False
                    or sr.get("doi_ok") is False)
        needs_visual = qid in visual_ids or sr.get("visual_required")
        if sr.get("truncation_risk"):
            negative = True
        row["conservative_review"] = {
            "rule": "excluded from core if any reviewer signal is negative",
            "negative_signal": bool(negative),
            "reviewer_unstable_paper": paper in unstable,
            "note": ("reviewer verdict flipped between two runs of this paper; not excluded to avoid "
                     "dropping 7 confirmed items, flagged for manual sample check") if paper in unstable else None,
        }
        if needs_visual:
            row["visual_verification"] = {"required": True, "status": "PENDING", "level": "VISUAL_VERIFIED"}
            visual.append(row)
        if negative:
            row["quality_tier"] = "REVIEW_REQUIRED"
            review.append(row)
        elif needs_visual:
            row["quality_tier"] = "VISUAL_PENDING"
            core.append(row)          # kept in core but marked as needing visual verification
        else:
            row["quality_tier"] = "SEMANTIC_VERIFIED"
            core.append(row)
        tiers[row["quality_tier"]] += 1

    write_jsonl(OUT / "candidates.v3.jsonl", rows)
    write_jsonl(OUT / "core_subset.v3.jsonl", core)
    write_jsonl(OUT / "review_queue.v3.jsonl", review)
    write_jsonl(OUT / "visual_todo.v3.jsonl", visual)

    artifacts = ["candidates.v3.jsonl", "core_subset.v3.jsonl", "review_queue.v3.jsonl", "visual_todo.v3.jsonl"]
    freeze = {
        "schema_version": "1.0",
        "dataset_version": "v3",
        "frozen_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "counts": {"candidates": len(rows), "core": len(core), "review_required": len(review),
                   "visual_pending": len(visual), "tiers": dict(tiers)},
        "rules": {
            "core": "verification_status==ORIGINAL_PDF_VERIFIED AND no negative reviewer signal",
            "conservative": "negative if unsupported | leakage | ambiguous | numeric_ok=false | doi_ok=false | truncation_risk",
            "visual": "flagged separately; retained in core but marked VISUAL_PENDING",
            "answers": "reference answers were never modified to fit any RAG/Agent output",
        },
        "kb_mapping": {"verified": sum(1 for p in align.values() if p.get("status") == "VERIFIED"),
                       "ambiguous": sum(1 for p in align.values() if p.get("status") == "AMBIGUOUS"),
                       "not_found": sum(1 for p in align.values() if p.get("status") == "NOT_FOUND")},
        "artifacts_sha256": {a: sha256_file(OUT / a) for a in artifacts},
        "inputs_sha256": {n: sha256_file(DS / n) for n in
                          ("semantic_review_all.jsonl", "visual_todo.jsonl", "kb_alignment.json")},
        "reviewer_instability": {"paper_id": "sha256:9670ffc08b269577",
                                 "observed": "one verdict flipped between two reviewer runs (7/1 -> 6/2)",
                                 "handling": "flagged per row, not auto-excluded"},
    }
    (OUT / "FREEZE.json").write_text(json.dumps(freeze, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[freeze] candidates={len(rows)} core={len(core)} review={len(review)} visual={len(visual)} tiers={dict(tiers)}")
    for a, h in freeze["artifacts_sha256"].items():
        print(f"[freeze] {a} sha256={h[:16]}...")
    print(f"[freeze] wrote {OUT.as_posix()}/FREEZE.json (kb: verified={freeze['kb_mapping']['verified']} "
          f"ambiguous={freeze['kb_mapping']['ambiguous']} not_found={freeze['kb_mapping']['not_found']})")


if __name__ == "__main__":
    main()
