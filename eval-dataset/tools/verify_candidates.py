#!/usr/bin/env python3
# Step 5 Stage 5.3 - independent programmatic verifier (v1).
#
# It does NOT trust the generator: for every evidence_ref it re-reads the extracted ORIGINAL PDF page text
# and checks (a) the page exists among the pages we sent, (b) short_quote occurs verbatim (whitespace/hyphen
# tolerant), (c) numeric tokens of the reference answer occur on the cited pages, (d) any DOI-shaped string in
# the answer occurs on the cited pages (anti-fabrication), (e) UNANSWERABLE has checked_scope + rationale.
#
# Verdicts: ORIGINAL_PDF_VERIFIED (all checks pass, text-level verification only) /
#           REVIEW_REQUIRED (typed reason) / REJECTED (no usable evidence).
# Outputs per paper: verified.jsonl, review_queue.jsonl, rejected.jsonl, verification_report.json
# (repository artifacts keep only short quotes - already inside the candidates).
#
# Usage: python -X utf8 eval-dataset/tools/verify_candidates.py --paper sha256:9670ffc08b269577

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORK = REPO / "eval-dataset/work/page_text"
PER_PAPER = REPO / "eval-dataset/per_paper"
CATEGORIES = ["FACT", "METHOD", "NUMERIC", "EVIDENCE_LOCATE", "SYNTHESIS", "UNANSWERABLE"]

WARN_CODES = ["SOURCE_MISSING", "PAGE_MISMATCH", "NUMERIC_MISMATCH", "DOI_MISMATCH",
              "AMBIGUOUS_QUESTION", "DUPLICATE", "UNANSWERABLE_UNVERIFIED",
              "PDF_EXTRACTION_LIMITATION", "OTHER"]


def norm(s: str) -> str:
    s = s.replace("\u00ad", "").replace("\u2010", "-").replace("\u2011", "-")
    return re.sub(r"\s+", " ", s).strip()


def loose(s: str) -> str:
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", norm(s).lower())


def load_pages(paper_id):
    safe = paper_id.replace(":", "_")
    pages = {}
    with (WORK / f"{safe}.jsonl").open(encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                o = json.loads(line)
                pages[int(o["pdf_page"])] = o.get("text", "")
    return pages


def numbers_in(text: str):
    return [m.group(0) for m in re.finditer(r"\d+(?:\.\d+)?", text or "")]


def significant(nums):
    out = []
    for n in nums:
        if len(n.replace(".", "")) >= 2:      # ignore single-digit noise (page numbers, list indexes)
            out.append(n)
    return out


def verify_one(cand, pages):
    reasons = []
    notes = []
    status = "ORIGINAL_PDF_VERIFIED"
    qtype = cand.get("question_type")
    if qtype not in CATEGORIES:
        reasons.append("OTHER")
        notes.append(f"illegal question_type={qtype}")

    refs = cand.get("evidence_refs") or []
    cited_texts = []
    for ref in refs:
        pg = ref.get("pdf_page")
        if pg is None or int(pg) not in pages:
            reasons.append("PAGE_MISMATCH")
            notes.append(f"pdf_page={pg} not among provided pages")
            continue
        page_text = pages[int(pg)]
        cited_texts.append(page_text)
        quote = ref.get("short_quote") or ""
        if not quote.strip():
            reasons.append("SOURCE_MISSING")
            notes.append(f"page {pg}: empty short_quote")
            continue
        if norm(quote) in norm(page_text) or loose(quote) in loose(page_text):
            ref["evidence_sha256"] = hashlib.sha256(norm(quote).encode("utf-8")).hexdigest()
        else:
            reasons.append("SOURCE_MISSING")
            notes.append(f"page {pg}: short_quote not found verbatim")

    if cand.get("answerability") == "UNANSWERABLE" or qtype == "UNANSWERABLE":
        if not (cand.get("checked_scope") or "").strip():
            reasons.append("UNANSWERABLE_UNVERIFIED")
            notes.append("missing checked_scope")
        if not (cand.get("unanswerable_rationale") or "").strip():
            reasons.append("UNANSWERABLE_UNVERIFIED")
            notes.append("missing unanswerable_rationale")
        if not refs:
            notes.append("no evidence_refs (allowed for UNANSWERABLE)")

    joined = " ".join(cited_texts)
    answer = cand.get("reference_answer") or ""
    missing_nums = [n for n in significant(numbers_in(answer)) if n not in norm(joined) and n not in norm(quote_join(refs))]
    if missing_nums:
        reasons.append("NUMERIC_MISMATCH")
        notes.append("numbers not found on cited pages: " + ",".join(missing_nums[:6]))

    dois = re.findall(r"10\.\d{4,9}/[^\s,;)]+", answer)
    if dois:
        for doi in dois:
            if doi not in norm(joined):
                reasons.append("DOI_MISMATCH")
                notes.append("DOI in answer not present on cited pages (possible fabrication)")
                break

    if cand.get("answerability") == "ANSWERABLE" and not refs:
        reasons.append("SOURCE_MISSING")
        notes.append("ANSWERABLE candidate without evidence_refs")

    if "SOURCE_MISSING" in reasons or "PAGE_MISMATCH" in reasons:
        status = "REJECTED" if not cited_texts else "REVIEW_REQUIRED"
    elif reasons:
        status = "REVIEW_REQUIRED"

    cand["verification_status"] = status
    cand["verification_method"] = "programmatic_page_text_match"
    cand["review_notes"] = notes
    return status, reasons


def quote_join(refs):
    return " ".join((r.get("short_quote") or "") for r in refs)


def atomic_write_jsonl(path, rows):
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    os.replace(tmp, path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--paper", required=True)
    args = ap.parse_args()

    safe = args.paper.replace(":", "_")
    src = PER_PAPER / safe / "candidates.jsonl"
    if not src.exists():
        raise SystemExit(f"[FATAL] candidates not found: {src}")
    pages = load_pages(args.paper)

    cands = [json.loads(line) for line in src.read_text(encoding="utf-8").splitlines() if line.strip()]
    verified, review, rejected, hist = [], [], [], {}
    for cand in cands:
        status, reasons = verify_one(cand, pages)
        hist[cand.get("question_type", "?")] = hist.get(cand.get("question_type", "?"), 0) + 1
        if status == "ORIGINAL_PDF_VERIFIED":
            verified.append(cand)
        elif status == "REVIEW_REQUIRED":
            review.append(cand)
        else:
            rejected.append(cand)

    out = PER_PAPER / safe
    atomic_write_jsonl(out / "verified.jsonl", verified)
    atomic_write_jsonl(out / "review_queue.jsonl", review)
    atomic_write_jsonl(out / "rejected.jsonl", rejected)

    report = {
        "paper_id": args.paper,
        "candidates": len(cands),
        "verified": len(verified),
        "review_required": len(review),
        "rejected": len(rejected),
        "by_category": hist,
        "verifier": "programmatic_page_text_match (independent of generator)",
        "limitations": [
            "text-level verification only: visual (image) table/figure checks are NOT performed",
            "answer-vs-question leakage and semantic duplication are not yet checked (needs the LLM checker pass)",
        ],
    }
    (out / "verification_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[verify] paper={args.paper} candidates={len(cands)} verified={len(verified)} "
          f"review={len(review)} rejected={len(rejected)} by_category={hist}")
    for c in review + rejected:
        print(f"  - {c['question_id']} {c.get('question_type')} -> {c['verification_status']} notes={c.get('review_notes')}")


if __name__ == "__main__":
    main()
