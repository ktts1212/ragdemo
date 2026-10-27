#!/usr/bin/env python3
# Step 5 - normalize per-paper artifacts to the step5 §7 schema and audit every record.
# Fixes: pdf_sha256 must be the FULL sha256 (was truncated to 16 hex); evidence_refs must carry
# printed_page / source_type / evidence_sha256; enums and required fields validated; unique question_id.
# Deterministic, no LLM. Run before aggregate.py so dataset-level files stay consistent.

import json
import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DS = REPO / "eval-dataset"
PER = DS / "per_paper"
CATEGORIES = {"FACT", "METHOD", "NUMERIC", "EVIDENCE_LOCATE", "SYNTHESIS", "UNANSWERABLE"}
DIFFICULTY = {"easy", "medium", "hard"}
ANSWERABILITY = {"ANSWERABLE", "UNANSWERABLE"}
REQUIRED = ["schema_version", "question_id", "paper_id", "pdf_sha256", "document_id", "knowledge_base_id",
            "question_type", "difficulty", "question", "reference_answer", "acceptable_answers",
            "numeric_tolerance", "answerability", "evidence_refs", "checked_scope", "unanswerable_rationale",
            "verification_status", "verification_method", "generation_method", "review_notes", "dataset_version"]
REF_REQUIRED = ["pdf_page", "printed_page", "section", "locator", "short_quote", "source_type", "evidence_sha256"]


def read_jsonl(p):
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] if p.exists() else []


def write_jsonl(p, rows):
    tmp = p.with_suffix(p.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    tmp.replace(p)


def main():
    manifest = json.loads((DS / "manifest.json").read_text(encoding="utf-8"))
    full_sha = {p["paper_id"]: p["pdf_sha256"] for p in manifest["papers"]}
    page_of = {p["paper_id"]: p.get("page_count") for p in manifest["papers"]}

    issues = []
    seen_ids = Counter()
    fixed = Counter()
    totals = Counter()

    for directory in sorted(PER.glob("sha256_*")):
        paper_id = "sha256:" + directory.name.split("_", 1)[1]
        for fname in ("candidates.jsonl", "verified.jsonl", "review_queue.jsonl", "rejected.jsonl"):
            path = directory / fname
            rows = read_jsonl(path)
            if not rows:
                continue
            for row in rows:
                # --- normalization ---
                if row.get("pdf_sha256") != full_sha.get(paper_id):
                    row["pdf_sha256"] = full_sha.get(paper_id)          # full sha256 (spec §7)
                    if fname == "candidates.jsonl":
                        fixed["pdf_sha256"] += 1
                row.setdefault("dataset_version", "v1")
                row.setdefault("schema_version", "1.0")
                row.setdefault("generation_method", "llm_from_pdf_pages")
                row.setdefault("review_notes", [])
                row.setdefault("acceptable_answers", [])
                row.setdefault("numeric_tolerance", None)
                row.setdefault("checked_scope", None)
                row.setdefault("unanswerable_rationale", None)
                for ref in row.get("evidence_refs") or []:
                    ref.setdefault("printed_page", None)
                    ref.setdefault("source_type", "original_pdf")
                    ref.setdefault("section", None)
                    ref.setdefault("locator", None)
                    ref.setdefault("evidence_sha256", None)
                    ref.setdefault("pdf_page", None)
                    ref.setdefault("short_quote", "")
                # --- audit ---
                qid = row.get("question_id")
                seen_ids[(fname, qid)] += 1
                for key in REQUIRED:
                    if key not in row:
                        issues.append(f"{qid}: missing field {key}")
                if row.get("question_type") not in CATEGORIES:
                    issues.append(f"{qid}: illegal question_type={row.get('question_type')}")
                if row.get("difficulty") not in DIFFICULTY:
                    issues.append(f"{qid}: illegal difficulty={row.get('difficulty')}")
                if row.get("answerability") not in ANSWERABILITY:
                    issues.append(f"{qid}: illegal answerability={row.get('answerability')}")
                if not (row.get("question") or "").strip():
                    issues.append(f"{qid}: empty question")
                if not (row.get("reference_answer") or "").strip():
                    issues.append(f"{qid}: empty reference_answer")
                if row.get("answerability") == "ANSWERABLE" and not (row.get("evidence_refs") or []):
                    issues.append(f"{qid}: ANSWERABLE without evidence_refs")
                if row.get("question_type") == "UNANSWERABLE":
                    if not (row.get("checked_scope") or "").strip():
                        issues.append(f"{qid}: UNANSWERABLE without checked_scope")
                    if not (row.get("unanswerable_rationale") or "").strip():
                        issues.append(f"{qid}: UNANSWERABLE without rationale")
                for ref in row.get("evidence_refs") or []:
                    for key in REF_REQUIRED:
                        if key not in ref:
                            issues.append(f"{qid}: evidence_ref missing {key}")
                    pg = ref.get("pdf_page")
                    pc = page_of.get(paper_id)
                    if not isinstance(pg, int) or pg < 1 or (isinstance(pc, int) and pg > pc):
                        issues.append(f"{qid}: pdf_page={pg} out of range 1..{pc}")
            write_jsonl(path, rows)
            totals[fname] += len(rows)

    dup = {key: n for key, n in seen_ids.items() if n > 1}
    for (fname, qid), n in dup.items():
        issues.append(f"{qid}: duplicated question_id within {fname} ({n}x)")
    # cross-file consistency: verified/review/rejected must be subsets of candidates (same question_id)
    for directory in sorted(PER.glob("sha256_*")):
        cand_ids = {r.get("question_id") for r in read_jsonl(directory / "candidates.jsonl")}
        for fname in ("verified.jsonl", "review_queue.jsonl", "rejected.jsonl"):
            for row in read_jsonl(directory / fname):
                if row.get("question_id") not in cand_ids:
                    issues.append(f"{row.get('question_id')}: present in {fname} but missing from candidates.jsonl")

    print(f"[normalize] fixed_full_sha256={fixed['pdf_sha256']}")
    print(f"[normalize] rows: " + ", ".join(f"{k}={v}" for k, v in totals.items()))
    print(f"[audit] issues={len(issues)} duplicate_ids={len(dup)}")
    for line in issues[:20]:
        print("  - " + line)
    if issues:
        print("[audit] RESULT=FAIL (fix before trusting the dataset)")
        sys.exit(1)
    print("[audit] RESULT=OK (all required fields, enums, page ranges, ids unique)")


if __name__ == "__main__":
    main()
