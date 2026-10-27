#!/usr/bin/env python3
# Step 5 - independent LLM semantic reviewer (separate role/context from the generator).
#
# Independence measures:
#  * Different system prompt (adversarial reviewer, not a generator) and a fresh context.
#  * Receives the RAW page text of the cited pages (re-read from work/page_text, not the generator's quotes),
#    plus the list of pages that were NOT provided, so it can flag truncation risk honestly.
#  * It must judge support/leakage/ambiguity/duplicates/visual dependency per question and answer strictly
#    in JSON; it never sees the generator's own verdicts.
#  * Never calls the system under test (/api/chat, /api/agent/chat).
#
# Usage: python -X utf8 eval-dataset/tools/check_semantics.py [--paper sha256:xxxx] [--all]

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
PER = REPO / "eval-dataset/per_paper"
DS = REPO / "eval-dataset"

SYSTEM_PROMPT = """你是**严格的评测集独立审核员**（与生成器完全分离，禁止参考任何生成器结论）。
你会收到：某篇论文被引用页面的**原始文本**（可能不完整）、未被提供的页码列表、以及若干"问题+参考答案+引用"条目。
你的职责是**独立复核**，默认怀疑：只有原始文本能直接支撑参考答案时才给通过。

对每个条目逐项判断并在 reasons 里给出简短依据：
1. supported：原始页面文本是否**足以支撑**参考答案（不是"看起来合理"）。
2. leakage：问题是否**直接泄漏/包含**了答案（例如问题里已写出关键数值或结论）。
3. ambiguous：问题是否含混、缺少必要限定（数据集/任务/条件/指标名），导致无法唯一判定。
4. numeric_ok：若答案含实验数字，数字是否与原文一致，且**数据集/模型/指标**三者是否对应正确（对不上或原文未出现该组合 ⇒ false）。
5. doi_ok：若答案含 DOI/引用，是否与原文一致（原文无 ⇒ 不要给 true，写 null 并在 reasons 说明）。
6. duplicate_of：与本次列表中**语义高度相似**的其他 question_id（写数组；无则空数组）。
7. visual_required：该题是否**必须核对表格/曲线/图像**才能确认（例如数值只存在于表格图像、图注中，或文本层缺失/错位）。
8. truncation_risk：若在提供的页面中找不到支撑证据，且未被提供的页面很可能包含证据 ⇒ true。
禁止臆测；原文缺失就如实说明。

只输出 JSON：
{"paper_id":"...","items":[{"question_id":"...","verdict":"SEMANTIC_VERIFIED|REVIEW_REQUIRED","supported":true,
 "leakage":false,"ambiguous":false,"numeric_ok":true,"doi_ok":null,"duplicate_of":[],"visual_required":false,
 "truncation_risk":false,"reasons":["..."]}]}"""


def read_deepseek_config():
    lines = YML.read_text(encoding="utf-8").splitlines()
    cfg, in_models, in_ds = {}, False, False
    for raw in lines:
        line = raw.rstrip()
        if re.match(r"^\s{2}models:\s*$", line):
            in_models = True
            continue
        if in_models and re.match(r"^\s{4}deepseek:\s*$", line):
            in_ds = True
            continue
        if in_ds:
            if re.match(r"^\s{4}\S", line) and not re.match(r"^\s{4}deepseek:", line):
                break
            m = re.match(r"^\s{6}(base-url|api-key|model-name|temperature):\s*(.+?)\s*$", line)
            if m:
                cfg[m.group(1)] = m.group(2)
    if "api-key" not in cfg:
        raise SystemExit("[FATAL] deepseek api-key not found")
    return cfg


def call_api(cfg, system_prompt, user_prompt, timeout=900):
    payload = {
        "model": cfg["model-name"],
        "temperature": 0.0,
        "stream": False,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_prompt}],
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last = None
    for suffix in ("/chat/completions", "/v1/chat/completions"):
        url = cfg["base-url"].rstrip("/") + suffix
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", "Bearer " + cfg["api-key"])
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"
            if e.code == 404:
                continue
            raise SystemExit("[FATAL] " + last)
    raise SystemExit("[FATAL] both endpoints failed: " + str(last))


def load_pages(paper_id):
    path = WORK / (paper_id.replace(":", "_") + ".jsonl")
    pages = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            o = json.loads(line)
            pages[int(o["pdf_page"])] = o.get("text", "")
    return pages


def check_paper(cfg, paper_id):
    safe = paper_id.replace(":", "_")
    rows = [json.loads(l) for l in (PER / safe / "candidates.jsonl").read_text(encoding="utf-8").splitlines()
            if l.strip()]
    pages = load_pages(paper_id)
    cited = sorted({int(r["pdf_page"]) for row in rows for r in (row.get("evidence_refs") or [])
                    if r.get("pdf_page")})
    provided = [p for p in cited if p in pages]
    missing_pages = [p for p in sorted(pages) if p not in provided]

    page_block = "\n\n".join(f"=== PAGE {p} ===\n{pages[p][:4000]}" for p in provided)
    items = [{
        "question_id": row["question_id"],
        "question_type": row.get("question_type"),
        "question": row.get("question"),
        "reference_answer": row.get("reference_answer"),
        "evidence_refs": [{"pdf_page": r.get("pdf_page"), "locator": r.get("locator"),
                           "short_quote": (r.get("short_quote") or "")[:200]}
                          for r in (row.get("evidence_refs") or [])],
        "answerability": row.get("answerability"),
        "checked_scope": row.get("checked_scope"),
    } for row in rows]

    user_prompt = (
        f"paper_id: {paper_id}\n"
        f"该论文存在但**未提供**给你的页码（若证据可能在这些人页，请置 truncation_risk=true）：{missing_pages}\n"
        f"下面只给了被引用页面的原始文本：\n\n{page_block}\n\n"
        f"待审核条目（共 {len(items)} 条）：\n{json.dumps(items, ensure_ascii=False, indent=1)}\n\n"
        "请逐条独立复核并只输出 JSON。"
    )
    resp = call_api(cfg, SYSTEM_PROMPT, user_prompt)
    usage = resp.get("usage") or {}
    content = resp["choices"][0]["message"]["content"]
    parsed = json.loads(content)
    return rows, parsed.get("items", []), usage, provided, missing_pages


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--paper")
    ap.add_argument("--all", action="store_true")
    args = ap.parse_args()

    cfg = read_deepseek_config()
    manifest = json.loads((DS / "manifest.json").read_text(encoding="utf-8"))
    targets = [args.paper] if args.paper else [p["paper_id"] for p in manifest["papers"]]

    total_usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0, "calls": 0}
    for paper_id in targets:
        safe = paper_id.replace(":", "_")
        out = PER / safe
        started = time.time()
        rows, verdicts, usage, provided, missing = check_paper(cfg, paper_id)
        by_id = {v.get("question_id"): v for v in verdicts}
        merged = []
        for row in rows:
            v = by_id.get(row["question_id"], {})
            row["semantic_review"] = {
                "verdict": v.get("verdict", "REVIEW_REQUIRED"),
                "supported": v.get("supported"),
                "leakage": v.get("leakage"),
                "ambiguous": v.get("ambiguous"),
                "numeric_ok": v.get("numeric_ok"),
                "doi_ok": v.get("doi_ok"),
                "duplicate_of": v.get("duplicate_of", []),
                "visual_required": v.get("visual_required"),
                "truncation_risk": v.get("truncation_risk"),
                "reasons": v.get("reasons", []),
            }
            merged.append(row)
        tmp = (out / "semantic_review.jsonl").with_suffix(".jsonl.tmp")
        with tmp.open("w", encoding="utf-8", newline="\n") as fh:
            for row in merged:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        os.replace(tmp, out / "semantic_review.jsonl")

        counts = {}
        for row in merged:
            key = row["semantic_review"]["verdict"]
            counts[key] = counts.get(key, 0) + 1
        visual = [r["question_id"] for r in merged if r["semantic_review"].get("visual_required")]
        leak = [r["question_id"] for r in merged if r["semantic_review"].get("leakage")]
        dup = {r["question_id"]: r["semantic_review"]["duplicate_of"] for r in merged
               if r["semantic_review"].get("duplicate_of")}
        trunc = [r["question_id"] for r in merged if r["semantic_review"].get("truncation_risk")]
        report = {
            "paper_id": paper_id,
            "reviewed": len(merged),
            "verdicts": counts,
            "visual_required": visual,
            "leakage_flagged": leak,
            "duplicate_groups": dup,
            "truncation_risk": trunc,
            "pages_provided": provided,
            "pages_not_provided": missing,
            "elapsed_seconds": round(time.time() - started, 1),
            "usage": {k: usage.get(k, "unknown") for k in ("prompt_tokens", "completion_tokens", "total_tokens")},
            "reviewer": "independent LLM reviewer (separate prompt, raw page text, temperature=0)",
        }
        (out / "semantic_review_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                                         encoding="utf-8")
        for k in total_usage:
            if k == "calls":
                total_usage[k] += 1
            elif str(usage.get(k, "")).isdigit():
                total_usage[k] += int(usage[k])
        print(f"[review] {paper_id} reviewed={len(merged)} verdicts={counts} visual={len(visual)} leak={len(leak)} "
              f"dup={len(dup)} trunc={len(trunc)} tokens={usage.get('total_tokens', 'unknown')} "
              f"elapsed={report['elapsed_seconds']}s")

    print(f"[review] TOTAL calls={total_usage['calls']} prompt={total_usage['prompt_tokens']} "
          f"completion={total_usage['completion_tokens']} total={total_usage['total_tokens']}")


if __name__ == "__main__":
    main()
