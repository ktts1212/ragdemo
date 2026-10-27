# eval-dataset —— 学术文献评测集（Step 5）

本目录承载 **评测集数据**（不是跑分 runner）。跑分 runner 是同级目录 `eval-harness/`（下一阶段使用）。

**规格来源**：`skills/agent/step5.md`（`literature-eval-dataset-builder`）。目标：用户提供的 15 篇 PDF × 约 5 题 ≈ 75 题，逐篇可追溯、可审计，**不得**用被测系统（`/api/chat`、`/api/agent/chat`）生成标准答案。

## 输入
- PDF：`src/test/resources/pdfs/`（只读，共 15 篇，ECG/心血管自监督学习主题）
- 复用已有解析能力（Marker/canonical、章节结构、原 PDF），不重复昂贵转换

## 目录与产物（对齐 step5 §8）
```
eval-dataset/
├── README.md               本文件
├── manifest.json           论文登记：paper_id(sha256 前16位)、文件名、sha256、字节数、页数(启发式)、
│                           document_id/kb_id（映射后才填，否则 null）、状态
├── run_config.json         模型/Prompt/Schema 版本、参数、PDF 清单哈希、运行日期（不含密钥）
├── candidates.jsonl        全部候选题（含待审/拒绝）
├── verified_dataset.jsonl  仅 ORIGINAL_PDF_VERIFIED（真正通过原 PDF 核验）的题
├── review_queue.jsonl      需人工核验的题与疑点
├── rejected.jsonl          未通过门禁及原因（可统计的错误类型）
├── per_paper/              每篇的概况/证据定位/运行状态（可单独重试而不重算其他篇）
├── quality_report.md       类别数量、通过率、拒绝原因分布、未核验图表、潜在偏差
├── usage_report.json       每篇/总 Token、调用次数、失败与重试（不可观测则标 unknown）
└── tools/                  确定性脚本（如 build_manifest.ps1）
```

## 证据等级（step5 §5）
- `ORIGINAL_PDF_VERIFIED`：对原 PDF 对应页的实际可提取文字/表格/页面图像做过核验且一致（视觉表格必须真的核对图像）
- `CANONICAL_ONLY`：仅在 Marker/canonical 中找到来源，未对原 PDF 核实
- `REVIEW_REQUIRED`：解析疑点/歧义/表格错位/图片不可核/推理过强
- `REJECTED`：无可查证依据、事实冲突、问题不合格

> 注意：canonical 可能带 `CANONICAL_PROVENANCE_UNVERIFIED` —— 引用 canonical ≠ 经原 PDF 核验。

## 运行阶段
| 阶段 | 状态 | 说明 |
|---|---|---|
| 5.0 登记（manifest/run_config） | ✅ 完成 | 确定性、无 LLM、输入只读；15 篇、paper_id=sha256 前16位 |
| 5.1 文档映射 + 真实页数 | ✅ 完成 | PDFBox 取得 **15/15 真实页数**（总 **232** 页，启发式 213 已修正）；与 kb=1（共 145 篇文档）按 sha256/大小+文件名映射：**命中 1/15**，其余 14 篇标 `NOT_MAPPED`（不猜）；产物 `doc_mapping.json` |
| 5.2-prep 逐页文本 | ✅ 完成 | PDFBox 逐页抽取 → `work/page_text/*.jsonl`（15 篇、939,331 字符）；**原文只落在 git-ignored 工作区**，仓库产物仅保留短引文 |
| 5.2 逐篇候选题生成（7–8 → 约 5） | ⏳ 待做 | 独立生成器（直连 DeepSeek HTTP + 严格 JSON schema，**按页定向**投喂，避免全文重复入上下文）；先跑通第 1 篇再批量 |
| 5.3 独立核验（检查器重读原 PDF 页） | ⏳ 待做 | 生成器/检查器分离；数值与 DOI 程序化一致性检查 |
| 5.4 汇总与质量门禁 | ✅ 完成 | v1：120 候选 → 118 `TEXT_MATCHED` 文本核验通过（2 待审） |
| 5.5 独立语义审核 + v2 分层 | ✅ 完成 | 独立审核器（分离提示词/上下文、**重读被引页原文**、temperature=0）⇒ **110 SEMANTIC_VERIFIED / 10 REVIEW_REQUIRED**；v2 门禁（含 truncation/visual/泄漏/歧义/数值/DOI）⇒ `verified_dataset.v2.jsonl` = **109 题** |
| 5.6 去重 / 图表待办 | ✅ 完成 | 语义重复 1 组；`visual_todo.jsonl` = **19 题**（NUMERIC/EVIDENCE_LOCATE 且 locator 含 Table/Figure/图/表） |
| 5.7 知识库对齐 | ✅ 完成（只读） | `kb_alignment.json`：VERIFIED 0 / **AMBIGUOUS 1**（该篇在 kb=1 被重复上传 **72** 份，判为一对多）/ **NOT_FOUND 14**（未入库）；导入须走既有入库流程，本阶段**不建不改**文献 |
| 5.8 RAG vs Agent 跑分 | ⏳ 下一阶段（`eval-harness/`） | 前置：14 篇入库 + 清理重复行 + 图像题处理 |

## 约束（务必遵守）
- 不修改生产 Graph/Tool/解析入库/数据库/原有业务测试/已入库论文
- 不虚构篇数与题数；少于 5 题就如实说明
- 不把真实 API key、MinIO 密钥、原文大段正文、完整句柄写入任何产物或日志
- 写入采用临时文件 + 原子替换，避免半条 JSONL
