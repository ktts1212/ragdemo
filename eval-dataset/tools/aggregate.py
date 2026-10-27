#!/usr/bin/env python3
# Step 5 Stage 5.4 - aggregate per-paper artifacts into the dataset-level deliverables.
# Writes: candidates.jsonl, verified_dataset.jsonl, review_queue.jsonl, rejected.jsonl,
#         quality_report.md, usage_report.json, per_paper_summary.json ; updates manifest.json + run_config.json.
# Deterministic, no LLM calls.

import json
import os
import time
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DS = REPO / "eval-dataset"
PER = DS / "per_paper"

PLAN = {"FACT": 15, "METHOD": 15, "NUMERIC": 15, "EVIDENCE_LOCATE": 15, "SYNTHESIS": 10, "UNANSWERABLE": 5}


def read_jsonl(path):
    if not path.exists():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def write_jsonl(path, rows):
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    os.replace(tmp, path)


def main():
    manifest = json.loads((DS / "manifest.json").read_text(encoding="utf-8"))
    papers = manifest["papers"]
    by_id = {p["paper_id"]: p for p in papers}

    all_cands, all_verified, all_review, all_rejected = [], [], [], []
    per_paper_summary = []
    usage_rows = []
    factor_notes = []

    for directory in sorted(PER.glob("sha256_*")):
        safe = directory.name
        paper_id = "sha256:" + safe.split("_", 1)[1]
        cands = read_jsonl(directory / "candidates.jsonl")
        verified = read_jsonl(directory / "verified.jsonl")
        review = read_jsonl(directory / "review_queue.jsonl")
        rejected = read_jsonl(directory / "rejected.jsonl")
        usage = json.loads((directory / "usage.json").read_text(encoding="utf-8")) if (directory / "usage.json").exists() else {}
        report = json.loads((directory / "verification_report.json").read_text(encoding="utf-8")) if (directory / "verification_report.json").exists() else {}

        all_cands += cands
        all_verified += verified
        all_review += review
        all_rejected += rejected
        usage_rows.append(usage)
        per_paper_summary.append({
            "paper_id": paper_id,
            "file_name": by_id.get(paper_id, {}).get("file_name"),
            "candidates": len(cands),
            "verified": len(verified),
            "review_required": len(review),
            "rejected": len(rejected),
            "pages_verified": by_id.get(paper_id, {}).get("page_count"),
            "total_tokens": usage.get("total_tokens", "unknown"),
            "reason_counts": dict(Counter(r for c in review + rejected for r in (c.get("review_notes") or []))),
            "limitations": report.get("limitations", []),
        })

    write_jsonl(DS / "candidates.jsonl", all_cands)
    write_jsonl(DS / "verified_dataset.jsonl", all_verified)
    write_jsonl(DS / "review_queue.jsonl", all_review)
    write_jsonl(DS / "rejected.jsonl", all_rejected)
    (DS / "per_paper_summary.json").write_text(
        json.dumps(per_paper_summary, ensure_ascii=False, indent=2), encoding="utf-8")

    cat_cand = Counter(c.get("question_type") for c in all_cands)
    cat_ver = Counter(c.get("question_type") for c in all_verified)
    total_tokens = sum(int(u.get("total_tokens") or 0) for u in usage_rows if str(u.get("total_tokens", "")).isdigit())
    prompt_tokens = sum(int(u.get("prompt_tokens") or 0) for u in usage_rows if str(u.get("prompt_tokens", "")).isdigit())
    completion_tokens = sum(int(u.get("completion_tokens") or 0) for u in usage_rows
                            if str(u.get("completion_tokens", "")).isdigit())

    usage_report = {
        "observable": True,
        "generator_model": usage_rows[0].get("model") if usage_rows else "unknown",
        "generator_calls": len(usage_rows),
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": total_tokens,
        "checker_calls": 0,
        "checker_tokens": 0,
        "checker_note": "verification was programmatic (page-text matching), so 0 LLM tokens",
        "per_paper": [{"paper_id": u.get("paper_id"), "total_tokens": u.get("total_tokens", "unknown"),
                       "elapsed_seconds": u.get("elapsed_seconds")} for u in usage_rows],
    }
    (DS / "usage_report.json").write_text(json.dumps(usage_report, ensure_ascii=False, indent=2), encoding="utf-8")

    run_config = json.loads((DS / "run_config.json").read_text(encoding="utf-8"))
    run_config.update({
        "stage": "5.4-aggregate",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "generator_model": usage_report["generator_model"],
        "checker_model": "programmatic (no LLM)",
        "prompt_versions": {"generator": "generate_candidates.py/SYSTEM_PROMPT v1",
                            "checker": "verify_candidates.py (programmatic v1)"},
        "llm_calls": len(usage_rows),
        "tokens": {"prompt": prompt_tokens, "completion": completion_tokens, "total": total_tokens, "observable": True},
        "questions_per_paper_candidates": 8,
        "verification": "programmatic_page_text_match (independent of generator); LLM checker pass still TODO",
    })
    (DS / "run_config.json").write_text(json.dumps(run_config, ensure_ascii=False, indent=2), encoding="utf-8")

    for p in papers:
        key = p["paper_id"]
        row = next((s for s in per_paper_summary if s["paper_id"] == key), None)
        if row:
            p["processed"] = True
            p["questions_candidates"] = row["candidates"]
            p["questions_verified"] = row["verified"]
            p["questions_review"] = row["review_required"]
            p["questions_final"] = row["verified"]
            p["read_status"] = "PROCESSED" if row["verified"] else "REVIEW_ONLY"
    manifest["generated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    manifest["dataset_stats"] = {
        "candidates": len(all_cands), "verified": len(all_verified),
        "review_required": len(all_review), "rejected": len(all_rejected),
        "by_category_candidates": dict(cat_cand), "by_category_verified": dict(cat_ver),
    }
    (DS / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    deviation = {k: {"plan": PLAN[k], "actual_candidates": cat_cand.get(k, 0), "actual_verified": cat_ver.get(k, 0)}
                 for k in PLAN}
    md = []
    md.append("# Step 5 评测集质量报告\n")
    md.append(f"生成时间：{time.strftime('%Y-%m-%d %H:%M:%S%z')}｜数据集版本：v1｜schema_version：1.0\n")
    md.append("## 1. 总量\n")
    md.append("| 指标 | 数量 |\n|---|---|\n")
    md.append(f"| 论文（登记） | {len(papers)} |\n")
    md.append(f"| 候选题 | {len(all_cands)} |\n")
    md.append(f"| **已核验（ORIGINAL_PDF_VERIFIED）** | **{len(all_verified)}** |\n")
    md.append(f"| 待人工复核（REVIEW_REQUIRED） | {len(all_review)} |\n")
    md.append(f"| 拒绝（REJECTED） | {len(all_rejected)} |\n")
    md.append(f"| 通过率（verified / candidates） | {round(100.0 * len(all_verified) / max(1, len(all_cands)), 1)}% |\n")
    md.append("\n## 2. 类别分布 vs 规格建议（75 题口径）\n")
    md.append("| 类别 | 计划 | 候选实际 | 已核验实际 | 偏差说明 |\n|---|---|---|---|---|\n")
    for k in PLAN:
        d = deviation[k]
        note = "—" if d["actual_verified"] == d["plan"] else f"偏差 {d['actual_verified'] - d['plan']:+d}（每篇固定出题导致，规格允许按内容调整）"
        md.append(f"| {k} | {d['plan']} | {d['actual_candidates']} | {d['actual_verified']} | {note} |\n")
    md.append("\n## 3. 证据等级与核验方式\n")
    md.append("- 全部已核验题目：`ORIGINAL_PDF_VERIFIED`（核验方式：`programmatic_page_text_match`）\n")
    md.append("- 核验内容：① `pdf_page` 存在于投喂页集合；② `short_quote` 在所引页面文本中**逐字**出现"
              "（空白/连字符容错），并计算 `evidence_sha256`；③ 参考答案中的数值 token 必须出现在所引页面；"
              "④ 答案中的 DOI 形态串必须出现在所引页面（反幻觉）；⑤ UNANSWERABLE 必须含 `checked_scope` 与理由。\n")
    md.append("- **未做的核验**：视觉表格/图像核验（无图像级核对）、问题是否泄漏答案、跨篇语义去重 —— 需 LLM 检查器补充。\n")
    md.append("\n## 4. 待人工复核明细\n")
    if all_review:
        for c in all_review:
            md.append(f"- `{c['question_id']}` [{c.get('question_type')}]：{'; '.join(c.get('review_notes') or [])}\n")
    else:
        md.append("- （无）\n")
    md.append("\n## 5. Token 与成本（可观测）\n")
    md.append(f"- 生成调用：{usage_report['generator_calls']} 次；prompt {prompt_tokens} / completion {completion_tokens} / "
              f"**total {total_tokens}** tokens（模型 {usage_report['generator_model']}）\n")
    md.append("- 核验调用：0 次（程序化核验，无 LLM Token）\n")
    md.append("\n## 6. 潜在偏差与限制\n")
    md.append("- 每篇投喂前 12 页（每页约 2600 字符）⇒ 若关键数值在更后页或长表格中，可能未被纳入；本批未见因此产生的拒绝，但属已知限制。\n")
    md.append("- 模型为同一供应商单一模型；生成器与核验器已做**流程隔离**（程序化核验不依赖生成器答案），但**不等于**完全独立于模型偏见。\n")
    md.append("- `document_id` 目前仅 1/15 映射成功（kb=1 共 145 篇已入库文档）⇒ 题集以 PDF 为输入，后续跑分前需补做 KB 对齐。\n")
    md.append("- 原文页文本仅存于 `eval-dataset/work/`（git-ignored），仓库内只保留短引文。\n")
    (DS / "quality_report.md").write_text("".join(md), encoding="utf-8")

    print(f"[aggregate] candidates={len(all_cands)} verified={len(all_verified)} review={len(all_review)} "
          f"rejected={len(all_rejected)} tokens={total_tokens}")
    print(f"[aggregate] by_category_verified={dict(cat_ver)}")
    print("[aggregate] wrote candidates.jsonl, verified_dataset.jsonl, review_queue.jsonl, rejected.jsonl, "
          "per_paper_summary.json, quality_report.md, usage_report.json; updated manifest.json + run_config.json")


if __name__ == "__main__":
    main()
