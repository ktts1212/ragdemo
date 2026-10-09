# 无编号 · rawLevel 相同却父子 —— 诊断报告（不自动修）

> 生成命令：`mvn test -Dtest=RealPaperQaTest#unnumberedRawLevelPairReport_forReview -Dchunker.qa.pairReport=true`
> 目的：人工逐条判断后，再决定是否值得为 skill §5.3（无编号同级纠偏）建立稳定规则

## 为什么只诊断、不自动修

`rawLevel` 相同**不是**可靠的同级信号。实测中大量 pair 是**合法父子**，例如
`raw=2/2  "3 Method" > "3.1 Event Reconstruction"` —— 编号 3 / 3.1 证明这是真父子。
因此凡是**无编号**的这类 pair，本报告只列出、不修改。

## 字段说明

| 字段 | 含义 |
|---|---|
| rawLevel p/c | Marker 声明的 heading depth（父 / 子） |
| treeLevel p/c | 最终树中的 level（父 / 子） |
| childBlocks / childChars | 子节点自身的内容量（内容多 → 更像真章节标题） |
| childGrand | 子节点的子节点数 |
| parentBlocks | 父节点自身的内容量 |
| prevSibling | 前一个同级标题（看局部模式） |

## 判定提示

1. 同一父下**多个子**都呈「rawLevel 相同 + 各有独立正文」→ 更像「同级被错嵌套」
2. 子标题是父标题的**语义下位**（`Event Reconstruction` ⊂ `Method`）→ 真父子，不要动
3. 子节点**自身无正文且无子节点** → 更可能是 Marker 层级噪声
4. 只有当①同一父下 ≥2 例、②语义上互相并列、③子各有独立正文 三条同时成立时，
   才值得考虑写 §5.3 规则；否则维持现状

---

## Hierarchical_Self-Supervised_Representation_Learning_Framework_for_Multivariate_Time_Series_Grounded_in_ECG_Analysis.json

候选 pair：0

（无）

## JAMC_A_jigsaw-based_autoencoder_with_masked_contrastive_learning_for_cardiovascular_disease_diagnosis.json

候选 pair：0

（无）

## Learning_ECG_Representations_via_Poly-Window_Contrastive_Learning.json

候选 pair：0

（无）

## LEARNING_GENERAL_REPRESENTATION_OF_12-LEAD_ECG_WITH_A_JOINT-EMBEDDING_PREDICTIVE_ARCHITECTURE.json

候选 pair：3

| 父标题 | 子标题 | rawLevel p/c | treeLevel p/c | childBlocks/chars | childGrand | parentBlocks | prevSibling |
|---|---|---|---|---|---|---|---|
| LEARNING GENERAL REPRESENTATION OF 12-LEAD ECG WITH A JOINT-EMBEDDING PREDICTIVE ARCHITECTURE | A Additional Experiments | 1 / 1 | 1 / 2 | 0 / 0 | 5 | 1 | 4 Experimental Settings |
| LEARNING GENERAL REPRESENTATION OF 12-LEAD ECG WITH A JOINT-EMBEDDING PREDICTIVE ARCHITECTURE | B Ablation Study | 1 / 1 | 1 / 2 | 0 / 0 | 3 | 1 | A Additional Experiments |
| LEARNING GENERAL REPRESENTATION OF 12-LEAD ECG WITH A JOINT-EMBEDDING PREDICTIVE ARCHITECTURE | E Case Analysis of UMAP Embeddings | 1 / 1 | 1 / 2 | 3 / 1223 | 3 | 1 | B Ablation Study |

## Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json

候选 pair：4

| 父标题 | 子标题 | rawLevel p/c | treeLevel p/c | childBlocks/chars | childGrand | parentBlocks | prevSibling |
|---|---|---|---|---|---|---|---|
| Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers | Comparison of 1-dimensional transformers | 1 / 1 | 1 / 2 | 5 / 2562 | 3 | 13 | Comparative experiments |
| Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers | Investigation of results | 1 / 1 | 1 / 2 | 0 / 0 | 2 | 13 | Comparison of 1-dimensional transformers |
| Methods | Datasets | 2 / 2 | 1 / 2 | 1 / 613 | 0 | 0 | - |
| Methods | Pre-processing | 2 / 2 | 1 / 2 | 3 / 1623 | 8 | 0 | Datasets |

---

## 汇总

- 论文数：5
- 无编号 · rawLevel 相同却父子 的候选 pair 总数：**7**
- 其中「子节点自身有正文」：**4**（更值得关注；其余更像是 Marker 层级噪声）
