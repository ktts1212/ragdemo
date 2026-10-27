#!/usr/bin/env python3
# Step 5 task 4 - evaluation-set readiness check (read-only, no LLM, no answer changes).
# Verifies the frozen v3 subset against the required fields and the KB mapping status, and reports
# exactly which questions are ready / blocked / flagged. Never edits the frozen artifacts.

import json
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DS = REPO / "eval-dataset"
FROZEN = DS / "frozen"

REQUIRED = ["question_id", "paper_id", "pdf_sha256", "question_type", "difficulty", "question",
            "reference_answer", "evidence_refs", "answerability", "quality_tier"]


def read_jsonl(p):
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def main():
    core = read_jsonl(FROZEN / "core_subset.v3.jsonl")
    semantic = [r for r in core if r.get("quality_tier") == "SEMANTIC_VERIFIED"]
    visual = [r for r in core if r.get("quality_tier") == "VISUAL_PENDING"]

    missing_fields, bad_evidence, no_mapping, mapped = [], [], [], []
    for row in core:
        qid = row.get("question_id")
        miss = [f for f in REQUIRED if not row.get(f) and row.get(f) != []]
        if miss:
            missing_fields.append({"question_id": qid, "missing": miss})
        refs = row.get("evidence_refs") or []
        if not refs:
            bad_evidence.append({"question_id": qid, "reason": "no evidence_refs"})
        else:
            for r in refs:
                if not isinstance(r.get("pdf_page"), int) or r["pdf_page"] < 1:
                    bad_evidence.append({"question_id": qid, "reason": f"bad pdf_page={r.get('pdf_page')}"})
                    break
        if row.get("kb_mapping_status") == "VERIFIED" and row.get("document_id"):
            mapped.append(qid)
        else:
            no_mapping.append({"question_id": qid, "paper_id": row.get("paper_id"),
                               "kb_mapping_status": row.get("kb_mapping_status")})

    report = {
        "core_total": len(core),
        "semantic_verified": len(semantic),
        "visual_pending": len(visual),
        "by_category": dict(Counter(r.get("question_type") for r in core)),
        "by_difficulty": dict(Counter(r.get("difficulty") for r in core)),
        "missing_required_fields": missing_fields,
        "bad_evidence_refs": bad_evidence,
        "questions_with_verified_kb_mapping": len(mapped),
        "questions_without_kb_mapping": len(no_mapping),
        "blocked_by_kb": no_mapping[:200],
        "gold_modified": False,
        "note": "read-only check; the 91 SEMANTIC_VERIFIED questions are the intended core; "
                "KB mapping is blocked until the eval KB is populated (no guessing)",
    }
    (DS / "readiness_check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[readiness] core={report['core_total']} semantic={report['semantic_verified']} "
          f"visual_pending={report['visual_pending']}")
    print(f"[readiness] by_category={report['by_category']} by_difficulty={report['by_difficulty']}")
    print(f"[readiness] missing_fields={len(missing_fields)} bad_evidence={len(bad_evidence)}")
    print(f"[readiness] kb_mapped={report['questions_with_verified_kb_mapping']} "
          f"kb_unmapped={report['questions_without_kb_mapping']}  (expected: 0 mapped until KB is populated)")
    print("[readiness] wrote eval-dataset/readiness_check.json")


if __name__ == "__main__":
    main()
