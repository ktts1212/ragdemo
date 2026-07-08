# Skill：Java 文献 RAG 项目 Hybrid Evaluation Harness 实现指南

## 1. 目标

为 Java / Spring Boot 文献 RAG 项目设计并实现一套 Hybrid RAG Evaluation Harness，用于系统化评估 RAG 检索与生成质量。

核心思想：

- Harness 自己设计和实现，掌控评测流程、数据记录、版本对比和失败归因。
- Ragas / DeepEval / 自定义 LLM Judge 作为可插拔自动评分器，用于减少人工校验成本。
- 人工抽检作为最终质量校准，重点检查低分样例和部分高分样例。
- 不把 Ragas 当成完整测试平台，而是作为 Harness 中的 evaluator 组件。

一句话总结：

> 自建 Harness 做流程闭环，Ragas/LLM Judge 做自动评分，人工抽检做最终校准。

---

## 2. 总体架构

推荐采用 Java 主项目 + Python 旁路评测模块的结构。

```text
Java / Spring Boot RAG 主服务
    ↓
返回 answer / contexts / sources / chunkIds / docIds / scores
    ↓
Python eval-harness
    ↓
自定义检索指标 Hit@K / MRR / expected_doc_hit
    ↓
Ragas / LLM Judge 自动评分
    ↓
failed_cases.csv
    ↓
人工抽检与错误归因
    ↓
调整 RAG 配置并重新评测

不要把 Ragas 强行嵌入 Java 主项目。
Ragas 是 Python 包，适合作为旁路评测工具调用 Java RAG API。

3. 推荐目录结构

在 Java 项目根目录下新增：

eval-harness/
├── README.md
├── requirements.txt
├── rag_client.py              # 调用 Java RAG API
├── run_eval.py                # 批量运行评测集
├── retrieval_eval.py          # 计算 Hit@K / MRR 等确定性指标
├── ragas_eval.py              # 接入 Ragas，可选
├── llm_judge_eval.py          # 自定义 LLM Judge，可选
└── evals/
    ├── datasets/
    │   └── literature_eval.csv
    ├── experiments/
    │   └── run_001/
    │       ├── rag_outputs.jsonl
    │       ├── rag_outputs.csv
    │       ├── retrieval_scores.csv
    │       ├── ragas_scores.csv
    │       └── failed_cases.csv
    └── logs/

各文件职责：

rag_client.py       只负责调用 Java RAG 接口
run_eval.py         批量跑固定问题集，并保存 RAG 输出
retrieval_eval.py   计算确定性检索指标
ragas_eval.py       调 Ragas 评估 faithfulness / relevance 等
llm_judge_eval.py   自定义 LLM-as-judge 评分，可替代或补充 Ragas
failed_cases.csv    输出失败样例，供人工抽检
4. Harness 的核心不是 Ragas，而是评测集

第一步要自己设计 literature_eval.csv。

建议字段：

case_id,question,reference_answer,expected_doc_ids,expected_keywords,question_type,difficulty

字段含义：

字段	作用
case_id	评测样例 ID
question	测试问题
reference_answer	参考答案，给 Ragas / LLM Judge 使用
expected_doc_ids	期望命中的文献 ID，用于 Hit@K / MRR
expected_keywords	期望上下文中出现的关键词
question_type	问题类型，如 method / dataset / result / comparison / limitation / negative
difficulty	难度，如 easy / medium / hard

示例：

case_id,question,reference_answer,expected_doc_ids,expected_keywords,question_type,difficulty
1,ECG-LFM 使用了哪些自监督目标？,ECG-LFM 使用 Wav2Vec2 对比损失、MLM 和 CMSC。,12,"Wav2Vec2;MLM;CMSC",method,medium
2,PTB-XL 是什么类型的数据集？,PTB-XL 是公开的大规模 12 导联 ECG 数据集。,8,"12-lead;ECG",dataset,easy
3,该论文是否使用 UK Biobank ECG 数据？,未看到该论文使用 UK Biobank ECG 数据的说明。,12,"UK Biobank",negative,hard

第一版建议先做 10～30 条，不要一开始追求数量。

推荐问题类型分布：

文献事实类：数据集、采样率、导联数、任务类型
方法结构类：模型结构、自监督目标、损失函数
实验结果类：AUC、Accuracy、下游任务结果
对比分析类：与 SimCLR、MAE、ECG-LFM 等方法比较
局限性类：作者讨论的限制、泛化问题
否定/未提及类：测试模型是否胡编

否定类问题很重要，例如：

该论文是否使用 UK Biobank ECG 数据？
该论文是否报告 5 年 MI 风险预测结果？
作者是否提供医生人工 ST 抬高标注？

好的 RAG 应该在文献未提及时回答“未看到相关说明”，而不是编造。

5. Java RAG 接口必须返回中间过程

Java Spring Boot 项目需要提供一个标准接口，例如：

POST /api/rag/answer

请求：

{
  "question": "ECG-LFM 使用了哪些自监督目标？"
}

返回必须包含：

{
  "question": "ECG-LFM 使用了哪些自监督目标？",
  "answer": "ECG-LFM 使用了 Wav2Vec2 对比损失、MLM 和 CMSC。",
  "contexts": [
    "ECG-LFM combines Wav2Vec 2.0 contrastive loss, masked language modeling, and CMSC objectives."
  ],
  "sources": [
    {
      "docId": 12,
      "docTitle": "ECG-LFM.pdf",
      "chunkId": 88,
      "score": 0.82
    }
  ],
  "latencyMs": 2341
}

关键字段：

answer      生成答案
contexts    检索到的 chunk 文本
sources     来源文献、chunkId、score
latencyMs   响应耗时

没有 contexts 和 sources，Harness 无法判断是检索失败还是生成失败。

6. 第一阶段：先实现 Retrieval Harness

第一阶段不要急着接 Ragas，先实现确定性检索指标。

核心流程：

读取 literature_eval.csv
→ 批量调用 Java RAG API
→ 保存 rag_outputs.jsonl
→ 解析 sources
→ 计算 Hit@1 / Hit@3 / Hit@5 / MRR
→ 输出 retrieval_scores.csv 和 failed_cases.csv

确定性指标：

指标	含义
Hit@1	top1 是否命中期望文献
Hit@3	top3 是否命中期望文献
Hit@5	top5 是否命中期望文献
MRR	正确文献排名的倒数
expected_doc_hit	是否命中期望文献
expected_keyword_hit	contexts 是否包含期望关键词

MRR 计算方式：

正确文献排第 1 位：MRR = 1
正确文献排第 2 位：MRR = 1/2
正确文献排第 5 位：MRR = 1/5
未命中：MRR = 0

这一步最重要，因为文献 RAG 很多问题本质上是 retrieval miss 或 ranking 差。

7. 第二阶段：接入 Ragas 或 LLM Judge

Ragas 不负责设计问题集，也不是完整 Harness。
它主要负责根据：

question
answer
contexts
ground_truth / reference_answer

评估：

指标	主要判断
faithfulness	answer 是否被 contexts 支持
answer_relevancy	answer 是否回答了 question
answer_correctness	answer 是否接近 reference_answer
context_precision	检索上下文是否相关且排序靠前
context_recall	contexts 是否覆盖 reference_answer 所需信息

理解方式：

Ragas 不是简单比较 answer 和 reference_answer。
它会同时看 question、answer、contexts、ground_truth 之间的关系。

例如：

answer vs ground_truth      判断答案是否正确
answer vs question          判断是否答题
answer vs contexts          判断是否忠实于检索上下文
contexts vs ground_truth    判断检索内容是否覆盖标准答案
contexts vs question        判断上下文是否相关

如果 Ragas 因 Python / LangChain 依赖问题卡住，可以暂时跳过，先实现自定义 llm_judge_eval.py。

自定义 LLM Judge 输入：

{
  "question": "...",
  "contexts": ["...", "..."],
  "answer": "...",
  "reference_answer": "..."
}

输出：

{
  "faithfulness": 4,
  "answer_relevance": 5,
  "answer_correctness": 4,
  "error_type": "no_error",
  "comment": "答案基本正确，且由上下文支持。"
}
8. 第三阶段：失败样例归因

Harness 不应该只输出分数，还要输出失败原因。

推荐错误类型：

error_type	含义	优化方向
retrieval_miss	没检索到正确文献	改 embedding、chunk、query rewrite
poor_ranking	正确文献有但排名靠后	加 rerank，调 topK
context_noise	检索内容太杂	降 topK、加 rerank、优化 chunk
hallucination	上下文没有但模型编了	改 prompt，加引用约束
incomplete_answer	答案不完整	增加 topK，改 prompt
citation_error	引用来源错误	修 source 绑定逻辑
stale_context	检索到旧版本内容	加版本/时间过滤
no_error	正常	保留

第一版至少支持：

retrieval_miss
poor_ranking
context_noise
hallucination
incomplete_answer
9. 第四阶段：人工抽检

自动评分不能完全替代人工检查，尤其是医学文献 RAG。

人工抽检策略：

1. 必看低分样例
2. 抽查部分高分样例，防止自动评分误判
3. 检查 negative 问题是否发生幻觉
4. 检查引用 sources 是否真的支持答案
5. 把发现的问题补充进 eval dataset

人工抽检重点：

答案是否真的来自 context
context 是否来自正确文献
是否引用错误 chunk
是否把多个文献混在一起
是否对“未提及”问题胡编
参考答案本身是否需要修正

Harness 的可靠性来自：

固定评测集
可重复运行
确定性指标
自动评分
人工抽检
失败样例沉淀

不是单纯来自 Ragas。

10. 第五阶段：多版本对比

Harness 的核心价值是对比不同 RAG 配置，而不是只跑一次。

要比较的配置：

chunk_size: 500 / 800 / 1200
chunk_overlap: 100 / 200
topK: 5 / 10 / 20
rerank: off / on
query_rewrite: off / on
embedding_model: bge-m3 / text-embedding 等
prompt_version: v1 / v2 / v3
llm_model: deepseek / gpt / claude 等

每次实验保存为独立 run：

evals/experiments/
├── run_001_chunk500_top5/
├── run_002_chunk800_top5/
├── run_003_chunk800_top10/
├── run_004_chunk800_top20_rerank/

每个 run 保存：

rag_outputs.jsonl
retrieval_scores.csv
ragas_scores.csv
failed_cases.csv
summary.json

summary 示例：

{
  "run_name": "chunk800_top10_rerank",
  "hit_at_1": 0.62,
  "hit_at_3": 0.78,
  "hit_at_5": 0.84,
  "mrr": 0.69,
  "faithfulness": 0.88,
  "answer_relevancy": 0.91,
  "average_latency_ms": 3200,
  "failed_cases": 8
}
11. 推荐实现顺序

严格按这个顺序做，避免被 Ragas 依赖问题卡住。

Step 1：创建 eval-harness 目录
eval-harness/
├── requirements.txt
├── rag_client.py
├── run_eval.py
├── retrieval_eval.py
└── evals/
    ├── datasets/
    ├── experiments/
    └── logs/
Step 2：设计 10～30 条 eval cases

先人工设计文献问题，保存为：

evals/datasets/literature_eval.csv

每条包含：

question
reference_answer
expected_doc_ids
expected_keywords
question_type
difficulty
Step 3：修改 Java RAG 接口返回中间过程

必须返回：

answer
contexts
sources
docId
chunkId
score
latencyMs
Step 4：实现 rag_client.py

负责调用：

POST http://localhost:8080/api/rag/answer
Step 5：实现 run_eval.py

批量读取 literature_eval.csv，调用 Java RAG，保存：

rag_outputs.jsonl
rag_outputs.csv
Step 6：实现 retrieval_eval.py

计算：

Hit@1
Hit@3
Hit@5
MRR
expected_keyword_hit

输出：

retrieval_scores.csv
failed_cases.csv
Step 7：人工检查 failed_cases

根据失败原因判断：

chunk 是否切得不好
embedding 是否不合适
topK 是否太小
query rewrite 是否改坏问题
是否需要 rerank
prompt 是否导致幻觉
Step 8：接入 Ragas 或自定义 LLM Judge

等前面稳定后，再加入：

ragas_eval.py
或
llm_judge_eval.py
Step 9：做多版本对比

固定同一批 eval cases，对比不同配置：

chunk_size
topK
rerank
query rewrite
prompt version
embedding model
12. Ragas 在本项目中的定位

不要把 Ragas 当完整评测平台。

正确定位：

Ragas = 自动评分器
Harness = 评测系统

Ragas 负责：

faithfulness
answer relevancy
context precision
context recall
answer correctness

自己负责：

评测集设计
批量运行
RAG 输出记录
Hit@K / MRR
failed cases
人工抽检
版本对比
实验报告

如果 Ragas 因依赖问题无法使用，可以先使用自定义 LLM Judge，不阻塞 Harness 主体。

13. 简历 / 项目描述写法

可以写：

设计并实现 Hybrid RAG Evaluation Harness，支持自定义文献问答评测集、批量调用 Java RAG 服务、记录 answer/contexts/sources 等中间结果，并结合 Hit@K、MRR 等确定性检索指标与 Ragas/LLM-as-judge 自动评分，对 faithfulness、answer relevance、context recall 等维度进行评估；同时输出 failed cases 支持人工抽检与错误归因，用于对比 chunk size、topK、rerank、query rewrite 和 prompt 版本对问答质量的影响。

更简洁版：

实现 RAG Evaluation Harness，支持固定评测集、批量实验运行、Hit@K/MRR 检索评估、Ragas/LLM Judge 自动评分和人工抽检闭环，用于定位 retrieval miss、上下文噪声、幻觉和引用错误，并对比不同 RAG 配置效果。
14. 当前最优先任务

当前不要继续纠结 Ragas 安装问题。

优先完成：

1. 设计 literature_eval.csv，先写 10～30 条高质量文献问题
2. 修改 Java RAG API，确保返回 answer + contexts + sources
3. 写 rag_client.py
4. 写 run_eval.py
5. 写 retrieval_eval.py
6. 跑出 retrieval_scores.csv 和 failed_cases.csv
7. 人工检查失败样例
8. 后续再接 Ragas 或自定义 LLM Judge

第一版验收标准：

可以对同一批问题，自动批量调用 Java RAG 系统；
可以保存每条问题的 answer、contexts、sources；
可以计算 Hit@1、Hit@3、Hit@5、MRR；
可以输出 failed_cases.csv 给人工抽检。

达到这个标准后，再接入 Ragas 才有意义。
```
