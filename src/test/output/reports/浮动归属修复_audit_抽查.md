# Figure/Table 浮动归属修复 audit 抽查

> 用途：人工确认每次重挂是否合理（skill §8 可追踪性）
> 复现：`mvn test -Dtest=RealPaperQaTest#floatAudit_forReview -Dchunker.qa.floatAudit=true`

## 判定规则

- 编号来自 `label` 优先、`caption` 兜底；**类型+编号完全匹配**才可能移动
- 定义性引用（`shown in` / `see Table` …）优先；否则取浮动块**之前最近**的匹配引用
- 门槛：引用 Section ≠ 当前 Section **且** 页距 ≤ 3（无页码时 order 距 ≤ 120）
- 无引用 / 超距 / 不确定 → **不移动**（技能明确禁止为归零计数强行选择）

## 抽查要点

1. `pageDistance=0` 且 `orderDistance` 很小但跨了 Section → 典型 float 错位，应移
2. 前文某段"定义性引用"了属于后文的图 → 需确认是否被贪心拉走（风险模式）
3. `reason=nearest-preceding-reference` 的条目要重点看：是否只是"引用"而非"归属"

---

## Hierarchical_Self-Supervised_Representation_Learning_Framework_for_Multivariate_Time_Series_Grounded_in_ECG_Analysis.json

重挂 5 次。

| # | blockId | 编号 | from → to | 引用块 | 页距 | order距 | 依据 | 引用原文 |
|---|---|---|---|---|---|---|---|---|
| 1 | `/page/4/Diagram/0` | FIGURE:2 | `/page/3/SectionHeader/5` → `/page/4/SectionHeader/2` | `/page/4/Text/3` | 0 | 3 | nearest-preceding-reference | ations. We refer to Figure 2 for the schematic of the model. |
| 2 | `/page/5/Diagram/0` | FIGURE:3 | `/page/4/SectionHeader/5` → `/page/5/SectionHeader/3` | `/page/5/Text/4` | 0 | 4 | nearest-preceding-reference | ection, we refer to Figure 3 as a visual guide to facilitate the und |
| 3 | `/page/9/Figure/0` | FIGURE:4 | `/page/8/SectionHeader/7` → `/page/8/SectionHeader/3` | `/page/8/Text/6` | 1 | 5 | nearest-preceding-reference | remaining sequence. Figure 4 provides visual examples of each scheme |
| 4 | `/page/24/Table/1` | TABLE:9 | `/page/23/SectionHeader/8` → `/page/23/SectionHeader/6` | `/page/23/Text/7` | 1 | 5 | nearest-preceding-reference | y a minimal margin. Table 9 presents the average and standard devia |
| 5 | `/page/28/Table/1` | TABLE:11 | `/page/27/SectionHeader/8` → `/page/27/SectionHeader/6` | `/page/27/Text/7` | 1 | 5 | nearest-preceding-reference | Table 11 summarizes the configurations utilized  |

- ⚠️ FLOAT_REASSIGN_SKIPPED_BY_DISTANCE: TABLE:8 block=/page/23/Table/5 当前Section=/page/21/SectionHeader/4 引用Section=/page/11/SectionHeader/1 pageDistance=10 orderDistance=103

## JAMC_A_jigsaw-based_autoencoder_with_masked_contrastive_learning_for_cardiovascular_disease_diagnosis.json

重挂 11 次。

| # | blockId | 编号 | from → to | 引用块 | 页距 | order距 | 依据 | 引用原文 |
|---|---|---|---|---|---|---|---|---|
| 1 | `/page/7/Figure/2` | FIGURE:4 | `/page/6/SectionHeader/16` → `/page/6/SectionHeader/12` | `/page/6/Text/15` | 1 | 13 | definitional-reference | spects are shown in Fig. 4, while the third is presented in Table  |
| 2 | `/page/8/Figure/2` | FIGURE:6 | `/page/7/SectionHeader/17` → `/page/7/SectionHeader/9` | `/page/7/Text/10` | 1 | 15 | definitional-reference | d mask. As shown in Fig. 6, optimal performance is achieved at ��  |
| 3 | `/page/8/Figure/4` | FIGURE:7 | `/page/7/SectionHeader/17` → `/page/7/SectionHeader/14` | `/page/7/Text/15` | 1 | 15 | definitional-reference | (1–12). As shown in Fig. 7, both datasets perform better with an i |
| 4 | `/page/9/Figure/2` | FIGURE:8 | `/page/8/SectionHeader/14` → `/page/7/SectionHeader/16` | `/page/7/Text/18` | 2 | 30 | nearest-preceding-reference | to 11. According to Fig. 8, the Chapman dataset exhibits monotonic |
| 5 | `/page/9/Figure/4` | FIGURE:9 | `/page/8/SectionHeader/14` → `/page/7/SectionHeader/17` | `/page/8/Text/12` | 1 | 14 | nearest-preceding-reference | ational costs [17]. Fig. 9 demonstrates the models' reconstruction |
| 6 | `/page/10/Table/3` | TABLE:6 | `/page/8/SectionHeader/14` → `/page/8/SectionHeader/16` | `/page/8/Text/6` | 2 | 16 | definitional-reference | As shown in Table 6, significant improvements are achieved  |
| 7 | `/page/10/Table/10` | TABLE:7 | `/page/10/SectionHeader/13` → `/page/8/SectionHeader/14` | `/page/10/Text/11` | 0 | 4 | definitional-reference | As shown in Table 7, JAMC maintains stable performance acro |
| 8 | `/page/3/Diagram/4` | FIGURE:1 | `/page/2/SectionHeader/5` → `/page/2/SectionHeader/12` | `/page/2/Text/13` | 1 | 13 | definitional-reference | e is illustrated in Fig. 1, and each module is described in detail |
| 9 | `/page/3/Figure/5` | FIGURE:2 | `/page/2/SectionHeader/5` → `/page/2/SectionHeader/3` | `/page/2/Text/4` | 1 | 12 | definitional-reference | ations, as shown in Fig. 2. |
| 10 | `/page/5/Table/6` | TABLE:1 | `/page/3/SectionHeader/8` → `/page/5/SectionHeader/14` | `/page/5/Text/17` | 0 | 6 | definitional-reference | ion is presented in Table 1. |
| 11 | `/page/7/Table/8` | TABLE:4 | `/page/7/SectionHeader/16` → `/page/7/SectionHeader/12` | `/page/7/Text/13` | 0 | 3 | definitional-reference | ments). As shown in Table 4, SW demonstrates better performance. In |


## Learning_ECG_Representations_via_Poly-Window_Contrastive_Learning.json

重挂 2 次。

| # | blockId | 编号 | from → to | 引用块 | 页距 | order距 | 依据 | 引用原文 |
|---|---|---|---|---|---|---|---|---|
| 1 | `/page/2/Figure/0` | FIGURE:1 | `/page/1/SectionHeader/16` → `/page/1/SectionHeader/13` | `/page/1/Text/20` | 1 | 15 | definitional-reference | k is illustrated in Fig. 1. For each ECG in a batch of size N, we  |
| 2 | `/page/4/Figure/0` | FIGURE:2 | `/page/3/SectionHeader/12` → `/page/5/SectionHeader/7` | `/page/5/Text/8` | 1 | 21 | nearest-preceding-reference |  configuration (see Figure 2) not only converges faster, but also co |


## LEARNING_GENERAL_REPRESENTATION_OF_12-LEAD_ECG_WITH_A_JOINT-EMBEDDING_PREDICTIVE_ARCHITECTURE.json

重挂 14 次。

| # | blockId | 编号 | from → to | 引用块 | 页距 | order距 | 依据 | 引用原文 |
|---|---|---|---|---|---|---|---|---|
| 1 | `/page/6/Table/1` | TABLE:1 | `/page/5/SectionHeader/6` → `/page/7/SectionHeader/4` | `/page/7/Text/5` | 1 | 17 | nearest-preceding-reference | Table 1 presents the results of our linear eval |
| 2 | `/page/12/Figure/4` | FIGURE:8 | `/page/11/SectionHeader/7` → `/page/12/SectionHeader/6` | `/page/12/Text/8` | 0 | 4 | nearest-preceding-reference | from 10−2 to 10−5 . Figure 8 presents the AUC scores on the held-out |
| 3 | `/page/13/Table/1` | TABLE:10 | `/page/12/SectionHeader/6` → `/page/13/SectionHeader/2` | `/page/13/Text/6` | 0 | 5 | nearest-preceding-reference | Table 10 shows NCC results across datasets. Note |
| 4 | `/page/14/Figure/0` | FIGURE:9 | `/page/13/SectionHeader/12` → `/page/13/SectionHeader/7` | `/page/13/Text/11` | 1 | 5 | definitional-reference | As shown in Figure 9, the bootstrapped 95% confidence interv |
| 5 | `/page/17/Table/1` | TABLE:15 | `/page/16/SectionHeader/11` → `/page/16/SectionHeader/4` | `/page/16/Text/5` | 1 | 12 | definitional-reference | ing are provided in Table 15. In ECG-JEPAmb, the number of visible p |
| 6 | `/page/18/Figure/11` | FIGURE:11 | `/page/18/SectionHeader/8` → `/page/16/SectionHeader/11` | `/page/17/Text/8` | 1 | 20 | nearest-preceding-reference | across all 8 leads. Figure 11 illustrates the effects of filtering an |
| 7 | `/page/19/Figure/0` | FIGURE:12 | `/page/18/SectionHeader/8` → `/page/18/SectionHeader/1` | `/page/18/Text/3` | 1 | 11 | nearest-preceding-reference | ns of the baseline. Figure 12 consists of (a) an example of NSR and ( |
| 8 | `/page/3/Diagram/0` | FIGURE:3 | `/page/2/SectionHeader/9` → `/page/3/SectionHeader/2` | `/page/3/Text/3` | 0 | 3 | definitional-reference | , as illustrated in Figure 3. We subdivide the multi-channel ECG int |
| 9 | `/page/4/Diagram/0` | FIGURE:4 | `/page/3/SectionHeader/6` → `/page/4/SectionHeader/2` | `/page/4/Text/8` | 0 | 8 | nearest-preceding-reference | 68 in our case. See Figure 4 for an illustration. |
| 10 | `/page/7/Table/1` | TABLE:2 | `/page/6/SectionHeader/6` → `/page/7/SectionHeader/6` | `/page/7/Text/7` | 0 | 6 | nearest-preceding-reference |  as in Section 5.1. Table 2 presents the results. Notably, ECG-JEPA |
| 11 | `/page/8/Table/4` | TABLE:5 | `/page/7/SectionHeader/10` → `/page/8/SectionHeader/8` | `/page/9/Text/3` | 1 | 13 | nearest-preceding-reference | Table 5 reports the means and standard deviatio |
| 12 | `/page/9/Figure/0` | FIGURE:6 | `/page/8/SectionHeader/8` → `/page/9/SectionHeader/4` | `/page/9/Text/8` | 0 | 8 | nearest-preceding-reference | Figure 6 provides a qualitative example of ECG s |
| 13 | `/page/10/Table/1` | TABLE:6 | `/page/9/SectionHeader/9` → `/page/9/SectionHeader/4` | `/page/9/Text/8` | 1 | 5 | nearest-preceding-reference | JEPArb encoder, and Table 6 shows that fine-tuning substantially im |
| 14 | `/page/11/Table/1` | TABLE:7 | `/page/10/SectionHeader/9` → `/page/10/SectionHeader/6` | `/page/10/Text/7` | 1 | 6 | nearest-preceding-reference | Table 7 presents the results of our evaluation  |


## Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json

重挂 4 次。

| # | blockId | 编号 | from → to | 引用块 | 页距 | order距 | 依据 | 引用原文 |
|---|---|---|---|---|---|---|---|---|
| 1 | `/page/5/Table/10` | TABLE:3 | `/page/5/SectionHeader/8` → `/page/5/SectionHeader/1` | `/page/5/Text/3` | 0 | 7 | definitional-reference | From the results in Table 3 the dierence in AUROC score between all |
| 2 | `/page/6/Table/1` | TABLE:4 | `/page/6/SectionHeader/8` → `/page/5/SectionHeader/8` | `/page/6/Text/6` | 0 | 8 | nearest-preceding-reference | e results from Table 4 demonstrate that with only a single tra |
| 3 | `/page/8/Figure/3` | FIGURE:2 | `/page/7/SectionHeader/11` → `/page/7/SectionHeader/3` | `/page/7/Text/6` | 1 | 17 | nearest-preceding-reference |  (consistent with). Figure 2 shows a granular depiction of the issue |
| 4 | `/page/11/Table/14` | TABLE:6 | `/page/11/SectionHeader/12` → `/page/11/SectionHeader/4` | `/page/11/Text/5` | 0 | 9 | nearest-preceding-reference |  to the datasets in Table 6 should be noted. 268,812 samples from t |

- ⚠️ FLOAT_REASSIGN_SKIPPED_BY_DISTANCE: FIGURE:1 block=/page/1/Figure/6 当前Section=/page/0/SectionHeader/3 引用Section=/page/7/SectionHeader/11 pageDistance=6 orderDistance=98

---

## 汇总

- 论文数：5
- 浮动块总数（Figure+Table）：86
- 实际重挂次数：36（移动率 0.42）
