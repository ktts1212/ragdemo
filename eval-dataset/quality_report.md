# Step 5 评测集质量报告
生成时间：2026-10-16 01:22:55+0800｜数据集版本：v1｜schema_version：1.0
## 1. 总量
| 指标 | 数量 |
|---|---|
| 论文（登记） | 15 |
| 候选题 | 120 |
| **已核验（ORIGINAL_PDF_VERIFIED）** | **118** |
| 待人工复核（REVIEW_REQUIRED） | 2 |
| 拒绝（REJECTED） | 0 |
| 通过率（verified / candidates） | 98.3% |

## 2. 类别分布 vs 规格建议（75 题口径）
| 类别 | 计划 | 候选实际 | 已核验实际 | 偏差说明 |
|---|---|---|---|---|
| FACT | 15 | 17 | 17 | 偏差 +2（每篇固定出题导致，规格允许按内容调整） |
| METHOD | 15 | 30 | 30 | 偏差 +15（每篇固定出题导致，规格允许按内容调整） |
| NUMERIC | 15 | 28 | 28 | 偏差 +13（每篇固定出题导致，规格允许按内容调整） |
| EVIDENCE_LOCATE | 15 | 15 | 14 | 偏差 -1（每篇固定出题导致，规格允许按内容调整） |
| SYNTHESIS | 10 | 15 | 15 | 偏差 +5（每篇固定出题导致，规格允许按内容调整） |
| UNANSWERABLE | 5 | 15 | 14 | 偏差 +9（每篇固定出题导致，规格允许按内容调整） |

## 3. 证据等级与核验方式
- 全部已核验题目：`ORIGINAL_PDF_VERIFIED`（核验方式：`programmatic_page_text_match`）
- 核验内容：① `pdf_page` 存在于投喂页集合；② `short_quote` 在所引页面文本中**逐字**出现（空白/连字符容错），并计算 `evidence_sha256`；③ 参考答案中的数值 token 必须出现在所引页面；④ 答案中的 DOI 形态串必须出现在所引页面（反幻觉）；⑤ UNANSWERABLE 必须含 `checked_scope` 与理由。
- **未做的核验**：视觉表格/图像核验（无图像级核对）、问题是否泄漏答案、跨篇语义去重 —— 需 LLM 检查器补充。

## 4. 待人工复核明细
- `sha256_53384eda3bd3967c-q06` [EVIDENCE_LOCATE]：numbers not found on cited pages: 3.2
- `sha256_53384eda3bd3967c-q08` [UNANSWERABLE]：numbers not found on cited pages: 3.2

## 5. Token 与成本（可观测）
- 生成调用：15 次；prompt 112149 / completion 155765 / **total 267914** tokens（模型 None）
- 核验调用：0 次（程序化核验，无 LLM Token）

## 6. 潜在偏差与限制
- 每篇投喂前 12 页（每页约 2600 字符）⇒ 若关键数值在更后页或长表格中，可能未被纳入；本批未见因此产生的拒绝，但属已知限制。
- 模型为同一供应商单一模型；生成器与核验器已做**流程隔离**（程序化核验不依赖生成器答案），但**不等于**完全独立于模型偏见。
- `document_id` 目前仅 1/15 映射成功（kb=1 共 145 篇已入库文档）⇒ 题集以 PDF 为输入，后续跑分前需补做 KB 对齐。
- 原文页文本仅存于 `eval-dataset/work/`（git-ignored），仓库内只保留短引文。
