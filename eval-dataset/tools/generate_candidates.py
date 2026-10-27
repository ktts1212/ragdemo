#!/usr/bin/env python3
# Step 5 Stage 5.2 - independent candidate-question generator (NOT the system under test).
#
# Design notes (per skills/agent/step5.md):
#  * Reads page text that was extracted beforehand (work/page_text/<sha256_16>.jsonl) - page-targeted input,
#    never the whole paper repeatedly.
#  * Calls the DeepSeek OpenAI-compatible endpoint directly (generator is independent from /api/chat and
#    /api/agent/chat, so gold answers cannot leak from the system under test).
#  * Writes per-paper artifacts atomically (tmp + os.replace) and records real token usage when reported.
#  * Never prints the API key, never prints verbatim paper text.
#
# Usage:
#   python -X utf8 eval-dataset/tools/generate_candidates.py --paper sha256:9670ffc08b269577
#       [--max-pages 12] [--chars-per-page 2600] [--target 8] [--dry-run]

import argparse
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
YML = REPO / "src/main/resources/application.yml"
WORK = REPO / "eval-dataset/work/page_text"
OUT_ROOT = REPO / "eval-dataset/per_paper"
DMAP = REPO / "eval-dataset/doc_mapping.json"

CATEGORIES = ["FACT", "METHOD", "NUMERIC", "EVIDENCE_LOCATE", "SYNTHESIS", "UNANSWERABLE"]

SYSTEM_PROMPT = """你是学术文献评测集构建器（独立于被测系统，禁止调用任何 RAG/Agent 接口）。
任务：仅依据下面提供的**原始 PDF 页面文本**，为该论文生成候选题，输出严格 JSON（不要任何解释文字）。

硬性规则：
1. 只能使用给定页面文本中的事实；不得引入外部知识、常识补全、其他论文或网页信息。
2. 数字、指标名（AUC/AUROC/F1/准确率等）、数据集、实验设置必须与页面文本完全一致，不得四舍五入或改写口径。
3. DOI、引用、作者、年份：若页面文本中没有，就不要写；绝不编造 DOI 或引用编号。
4. 每题必须给出 evidence_refs：pdf_page（必须是给定页面之一）、section（若有）、locator（表/图/段编号或位置描述）、
   short_quote（必须是页面文本中的**逐字**片段，长度 <= 160 字符，用于程序化核验）。
5. 覆盖以下类别（尽量均衡，总数 7~8 题）：FACT（论文事实）、METHOD（方法/架构/损失/预训练策略）、
   NUMERIC（具体实验数值）、EVIDENCE_LOCATE（定位支撑某结论的章节/页/表/图）、
   SYNTHESIS（跨段落综合，需 >= 2 条独立证据）、UNANSWERABLE（在该论文范围内无法回答的问题，需给出 checked_scope 与理由）。
6. UNANSWERABLE 必须说明"检查了哪些页面/章节仍无法得出"，不得仅因没看到就宣称不可回答。
7. 问题不得直接泄漏答案；不得是"请复述摘要"这类宽泛题；不得依赖措辞猜测。

输出 JSON 结构：
{"paper_title_guess": "...", "candidates": [{
  "question_type": "FACT|METHOD|NUMERIC|EVIDENCE_LOCATE|SYNTHESIS|UNANSWERABLE",
  "difficulty": "easy|medium|hard",
  "question": "...",
  "reference_answer": "...",
  "acceptable_answers": ["..."],
  "numeric_tolerance": null,
  "answerability": "ANSWERABLE|UNANSWERABLE",
  "evidence_refs": [{"pdf_page": 1, "section": "...", "locator": "...", "short_quote": "..."}],
  "checked_scope": null,
  "unanswerable_rationale": null,
  "verification_status": "CANDIDATE",
  "generation_method": "llm_from_pdf_pages"
}]}"""


def read_deepseek_config():
    """Extract DeepSeek connection settings from application.yml (values are never printed)."""
    text = YML.read_text(encoding="utf-8").splitlines()
    cfg = {}
    in_models = False
    in_deepseek = False
    for raw in text:
        line = raw.rstrip()
        if re.match(r"^\s{2}models:\s*$", line):
            in_models = True
            continue
        if in_models and re.match(r"^\s{4}deepseek:\s*$", line):
            in_deepseek = True
            continue
        if in_deepseek:
            if re.match(r"^\s{4}\S", line) and not re.match(r"^\s{4}deepseek:", line):
                break
            m = re.match(r"^\s{6}(base-url|api-key|model-name|temperature):\s*(.+?)\s*$", line)
            if m:
                cfg[m.group(1)] = m.group(2)
    missing = [k for k in ("base-url", "api-key", "model-name") if k not in cfg]
    if missing:
        raise SystemExit(f"[FATAL] missing deepseek config keys: {missing}")
    return cfg


def load_pages(paper_id, max_pages, chars_per_page):
    safe = paper_id.replace(":", "_")
    path = WORK / f"{safe}.jsonl"
    if not path.exists():
        raise SystemExit(f"[FATAL] page text not found: {path} (run ExtractPages.java first)")
    pages = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            pages.append((int(obj["pdf_page"]), obj.get("text", "")))
    pages.sort(key=lambda p: p[0])
    chosen = pages[:max_pages]
    body = []
    chars = 0
    for page_no, text in chosen:
        clipped = text[:chars_per_page]
        chars += len(clipped)
        body.append(f"=== PAGE {page_no} ===\n{clipped}")
    return chosen, "\n\n".join(body), chars


def build_meta(paper_id, pages, chars_sent, cfg):
    return {
        "paper_id": paper_id,
        "pages_sent": [p for p, _ in pages],
        "chars_sent": chars_sent,
        "model": cfg["model-name"],
        "temperature": cfg.get("temperature", "0.2"),
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }


def call_api(cfg, user_prompt, timeout=600):
    payload = {
        "model": cfg["model-name"],
        "temperature": float(cfg.get("temperature", 0.2)),
        "stream": False,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    for suffix in ("/chat/completions", "/v1/chat/completions"):
        url = cfg["base-url"].rstrip("/") + suffix
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", "Bearer " + cfg["api-key"])
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8")), url
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            print(f"[warn] HTTP {e.code} at {url}: {detail}", file=sys.stderr)
            if e.code == 404:
                continue
            raise
    raise SystemExit("[FATAL] both endpoint forms failed")


def atomic_write_jsonl(path, rows):
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    os.replace(tmp, path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--paper", required=True)
    ap.add_argument("--max-pages", type=int, default=12)
    ap.add_argument("--chars-per-page", type=int, default=2600)
    ap.add_argument("--target", type=int, default=8)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if args.max_pages * args.chars_per_page > 140000:
        raise SystemExit("[FATAL] prompt too large; reduce --max-pages/--chars-per-page")

    cfg = read_deepseek_config()
    pages, body, chars = load_pages(args.paper, args.max_pages, args.chars_per_page)
    safe = args.paper.replace(":", "_")
    out_dir = OUT_ROOT / safe
    out_dir.mkdir(parents=True, exist_ok=True)

    meta = build_meta(args.paper, pages, chars, cfg)
    (out_dir / "gen_prompt_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    doc_name = "unknown"
    if DMAP.exists():
        mapping = json.loads(DMAP.read_text(encoding="utf-8"))
        for p in mapping.get("papers", []):
            if p.get("paper_id") == args.paper:
                doc_name = p.get("file_name", "unknown")

    user_prompt = (
        f"论文文件名（仅供你定位，不要写进答案）：{doc_name}\n"
        f"目标候选题数量：{args.target}（覆盖 FACT/METHOD/NUMERIC/EVIDENCE_LOCATE/SYNTHESIS/UNANSWERABLE，尽量均衡）\n"
        f"以下是该论文的原始页面文本（页码为 PDF 物理页，1-based）：\n\n{body}\n\n"
        "请只输出 JSON 对象（json）。"
    )

    print(f"[gen] paper={args.paper} pages={len(pages)} chars={chars} model={cfg['model-name']} dry_run={args.dry_run}")
    if args.dry_run:
        print("[gen] dry-run: prompt built, API not called")
        return

    started = time.time()
    resp, url = call_api(cfg, user_prompt)
    elapsed = time.time() - started
    (out_dir / "gen_raw_response.json").write_text(json.dumps(resp, ensure_ascii=False, indent=2), encoding="utf-8")

    usage = resp.get("usage") or {}
    usage_doc = {
        "paper_id": args.paper,
        "endpoint": url,
        "elapsed_seconds": round(elapsed, 1),
        "prompt_tokens": usage.get("prompt_tokens", "unknown"),
        "completion_tokens": usage.get("completion_tokens", "unknown"),
        "total_tokens": usage.get("total_tokens", "unknown"),
        "observable": bool(usage),
    }
    (out_dir / "usage.json").write_text(json.dumps(usage_doc, ensure_ascii=False, indent=2), encoding="utf-8")

    content = resp["choices"][0]["message"]["content"]
    parsed = json.loads(content)
    candidates = parsed.get("candidates", [])
    rows = []
    for idx, cand in enumerate(candidates, start=1):
        cand["schema_version"] = "1.0"
        cand["dataset_version"] = "v1"
        cand["paper_id"] = args.paper
        cand["question_id"] = f"{args.paper.replace(':', '_')}-q{idx:02d}"
        cand["pdf_sha256"] = args.paper.replace("sha256:", "")
        cand["document_id"] = None
        cand["knowledge_base_id"] = None
        cand["verification_method"] = "candidate_only"
        cand["review_notes"] = []
        rows.append(cand)
    atomic_write_jsonl(out_dir / "candidates.jsonl", rows)

    hist = {}
    for row in rows:
        hist[row.get("question_type", "?")] = hist.get(row.get("question_type", "?"), 0) + 1
    print(f"[gen] candidates={len(rows)} histogram={hist}")
    print(f"[gen] usage: prompt={usage_doc['prompt_tokens']} completion={usage_doc['completion_tokens']} "
          f"total={usage_doc['total_tokens']} elapsed={usage_doc['elapsed_seconds']}s")
    print(f"[gen] wrote {out_dir.as_posix()}/candidates.jsonl (+gen_prompt_meta.json, gen_raw_response.json, usage.json)")


if __name__ == "__main__":
    main()
