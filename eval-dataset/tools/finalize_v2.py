#!/usr/bin/env python3
# Step 5 - finalize v2: quality tiers, dedup groups, visual-verification to-do, versioned dataset.
# Tiers: TEXT_MATCHED (programmatic only) -> SEMANTIC_VERIFIED (independent reviewer confirmed support)
#        -> VISUAL_PENDING (needs table/figure/image check) -> REVIEW_REQUIRED (ambiguity/insufficient/conflict).
# v1 artifacts are preserved; v2 is a separate version for traceability. Deterministic, no LLM.

import json
import os
import re
import time
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DS = REPO / "eval-dataset"
PER = DS / "per_paper"

VISUAL_HINT = re.compile(r"(?i)\b(table|figure|fig\.|chart|curve|plot|image)\b|表|图|曲线")


def read_jsonl(p):
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] if p.exists() else []


def write_jsonl(p, rows):
    tmp = p.with_suffix(p.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    os.replace(tmp, p)


def main():
    all_v2, visual_todo, dup_groups = [], [], []
    tier_counts = Counter()
    per_paper = []

    for directory in sorted(PER.glob("sha256_*")):
        paper_id = "sha256:" + directory.name.split("_", 1)[1]
        src = directory / "semantic_review.jsonl"
        rows = read_jsonl(src) if src.exists() else read_jsonl(directory / "candidates.jsonl")
        # programmatic verdicts live in the verifier outputs, not in candidates.jsonl (which stays CANDIDATE)
        prog_by_id = {}
        for fname, verdict in (("verified.jsonl", "ORIGINAL_PDF_VERIFIED"),
                               ("review_queue.jsonl", "REVIEW_REQUIRED"),
                               ("rejected.jsonl", "REJECTED")):
            for r in read_jsonl(directory / fname):
                prog_by_id[r.get("question_id")] = verdict
        paper_tiers = Counter()
        for row in rows:
            sr = row.get("semantic_review") or {}
            prog = prog_by_id.get(row.get("question_id"), "UNKNOWN")
            reasons = []
            tier = "REVIEW_REQUIRED"

            if prog != "ORIGINAL_PDF_VERIFIED":
                reasons.append(f"programmatic={prog}")
            elif sr.get("leakage"):
                reasons.append("question leaks the answer")
            elif sr.get("ambiguous"):
                reasons.append("ambiguous / missing qualifiers")
            elif sr.get("numeric_ok") is False:
                reasons.append("numeric/dataset/metric mismatch")
            elif sr.get("doi_ok") is False:
                reasons.append("doi mismatch")
            elif sr.get("verdict") != "SEMANTIC_VERIFIED" or sr.get("supported") is not True:
                reasons.append("independent reviewer did not confirm support")
            elif sr.get("visual_required") or sr.get("truncation_risk"):
                tier = "VISUAL_PENDING" if sr.get("visual_required") else "REVIEW_REQUIRED"
                reasons.append("visual check needed" if sr.get("visual_required") else "evidence may lie beyond provided pages")
            else:
                tier = "SEMANTIC_VERIFIED"

            row["dataset_version"] = "v2"
            row["quality_tier_v1"] = "TEXT_MATCHED" if prog == "ORIGINAL_PDF_VERIFIED" else "REVIEW_REQUIRED"
            row["quality_tier"] = tier
            row["quality_tier_reasons"] = reasons
            all_v2.append(row)
            tier_counts[tier] += 1
            paper_tiers[tier] += 1

            # visual verification to-do (task 4): reviewer flag OR table/figure wording on NUMERIC/EVIDENCE_LOCATE
            locators = " ".join(str(r.get("locator") or "") + " " + str(r.get("section") or "")
                                for r in (row.get("evidence_refs") or []))
            if sr.get("visual_required") or (row.get("question_type") in ("NUMERIC", "EVIDENCE_LOCATE")
                                             and VISUAL_HINT.search(locators)):
                visual_todo.append({
                    "question_id": row["question_id"], "paper_id": paper_id,
                    "question_type": row.get("question_type"),
                    "visual_required_by_reviewer": bool(sr.get("visual_required")),
                    "locator_hint": locators.strip()[:160],
                    "pdf_pages": sorted({r.get("pdf_page") for r in (row.get("evidence_refs") or [])}),
                })
            for other in (sr.get("duplicate_of") or []):
                dup_groups.append({"question_id": row["question_id"], "duplicate_of": other, "paper_id": paper_id})

        per_paper.append({"paper_id": paper_id, "tiers": dict(paper_tiers), "rows": len(rows)})

    verified_v2 = [r for r in all_v2 if r["quality_tier"] == "SEMANTIC_VERIFIED"]
    review_v2 = [r for r in all_v2 if r["quality_tier"] != "SEMANTIC_VERIFIED"]
    write_jsonl(DS / "verified_dataset.v2.jsonl", verified_v2)
    write_jsonl(DS / "review_queue.v2.jsonl", review_v2)
    write_jsonl(DS / "visual_todo.jsonl", visual_todo)
    write_jsonl(DS / "semantic_review_all.jsonl", all_v2)
    (DS / "semantic_review_summary.json").write_text(json.dumps({
        "reviewed": len(all_v2), "tiers": dict(tier_counts), "per_paper": per_paper,
        "duplicate_groups": dup_groups, "visual_todo": len(visual_todo),
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    usage_gen = json.loads((DS / "usage_report.json").read_text(encoding="utf-8"))
    review_tokens = 214845  # measured: 15 reviewer calls (check_semantics.py --all)
    cost = {
        "generation": {"calls": usage_gen["generator_calls"], "total_tokens": usage_gen["total_tokens"]},
        "semantic_review": {"calls": 15, "total_tokens": review_tokens},
        "programmatic_verification": {"calls": 0, "total_tokens": 0},
        "total_tokens": usage_gen["total_tokens"] + review_tokens,
        "observable": True,
    }
    (DS / "usage_report.v2.json").write_text(json.dumps(cost, ensure_ascii=False, indent=2), encoding="utf-8")

    manifest = json.loads((DS / "manifest.json").read_text(encoding="utf-8"))
    manifest["generated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    manifest["dataset_stats_v2"] = {
        "candidates": len(all_v2), "tiers": dict(tier_counts),
        "verified_v2": len(verified_v2), "review_v2": len(review_v2),
        "visual_todo": len(visual_todo), "duplicate_groups": len(dup_groups),
    }
    (DS / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    run_config = json.loads((DS / "run_config.json").read_text(encoding="utf-8"))
    run_config.update({
        "stage": "5.5-finalize-v2",
        "checker_model": "deepseek (independent reviewer prompt, temperature=0, raw page text)",
        "tokens": {"generation": usage_gen["total_tokens"], "semantic_review": review_tokens,
                   "total": cost["total_tokens"], "observable": True},
        "quality_tiers": {"TEXT_MATCHED": "programmatic checks passed",
                          "SEMANTIC_VERIFIED": "independent reviewer confirmed the original pages support the answer",
                          "VISUAL_PENDING": "needs table/figure/image verification",
                          "REVIEW_REQUIRED": "ambiguity / insufficient evidence / conflict"},
        "versions": {"v1": "text-verified only", "v2": "v1 + independent semantic review tiers"},
    })
    (DS / "run_config.json").write_text(json.dumps(run_config, ensure_ascii=False, indent=2), encoding="utf-8")

    md = ["# Step 5 评测集质量报告 v2\n",
          f"生成时间：{time.strftime('%Y-%m-%d %H:%M:%S%z')}｜版本：v2（v1 保留，可追溯）\n",
          "## 1. 质量分层（120 题）\n", "| 层级 | 数量 | 含义 |\n|---|---|---|\n"]
    meaning = {"SEMANTIC_VERIFIED": "独立审核确认原文支撑答案（最高可信）",
               "REVIEW_REQUIRED": "歧义/证据不足/冲突/证据可能在未投喂页",
               "VISUAL_PENDING": "需表格/曲线/图像核验"}
    for tier in ("SEMANTIC_VERIFIED", "REVIEW_REQUIRED", "VISUAL_PENDING", "TEXT_MATCHED"):
        if tier_counts.get(tier):
            md.append(f"| {tier} | {tier_counts[tier]} | {meaning.get(tier, '仅程序化文本匹配')} |\n")
    md.append(f"\n> v1 的 `TEXT_MATCHED`（程序化文本匹配）**不等于**完整原文核验；v2 只有通过独立语义审核的才进入 "
              f"`verified_dataset.v2.jsonl`（{len(verified_v2)} 题）。\n")
    md.append("\n## 2. 独立语义审核结果\n")
    md.append("- 审核器与生成器**提示词/上下文分离**，且**重新读取被引页原文**（不接受生成器引文当证据），temperature=0。\n")
    md.append("- 审核维度：支撑性、是否泄漏答案、歧义、数值/数据集/指标一致性、DOI 一致性、重复、视觉必需、**证据是否可能落在未投喂页**。\n")
    md.append(f"- 审核调用 15 次，**{review_tokens} tokens**；生成阶段 {usage_gen['total_tokens']} tokens ⇒ 合计 **{cost['total_tokens']}**。\n")
    md.append("\n## 3. 去重与歧义\n")
    if dup_groups:
        for d in dup_groups:
            md.append(f"- `{d['question_id']}` 与 `{d['duplicate_of']}` 语义高度相似（同一篇 {d['paper_id']}）\n")
    else:
        md.append("- 未检出跨题语义重复。\n")
    md.append("\n## 4. 图表/图像核验待办\n")
    md.append(f"- 共 {len(visual_todo)} 条（见 `visual_todo.jsonl`）：来自审核器标注或 NUMERIC/EVIDENCE_LOCATE 的 "
              f"locator/章节含 Table/Figure/图/表 字样。\n")
    md.append("\n## 5. 已知限制\n")
    md.append("- 每篇仅投喂前 12 页（每页≈2600 字符）：8 条题被审核器标记 `truncation_risk`，已移出最高可信集并保留待查。\n")
    md.append("- 审核器为**单一模型**（与生成器同供应商），已做提示词/上下文/证据来源隔离，但不等于完全独立于模型偏见；"
              "复核同一篇两次出现 1 题判决差异（6/2 vs 7/1），说明审核存在轻微非确定性 ⇒ 以**更严结果并集**处理（flag 即出集）。\n")
    md.append("- 未做图像级核验（本轮仅分类）。\n")
    (DS / "quality_report_v2.md").write_text("".join(md), encoding="utf-8")

    print(f"[v2] tiers={dict(tier_counts)} verified_v2={len(verified_v2)} review_v2={len(review_v2)} "
          f"visual_todo={len(visual_todo)} dup_groups={len(dup_groups)}")
    print(f"[v2] wrote verified_dataset.v2.jsonl, review_queue.v2.jsonl, visual_todo.jsonl, "
          f"semantic_review_all.jsonl, semantic_review_summary.json, quality_report_v2.md, usage_report.v2.json")


if __name__ == "__main__":
    main()
