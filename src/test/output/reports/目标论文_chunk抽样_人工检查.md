# 目标论文 chunk 抽样（人工检查用）

> 论文：Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
> documentId：`Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json`
> 抽样数：**18 / 126**（定点覆盖 + 固定种子 42 随机补齐，结果可复现）
> 复现命令：`mvn test -Dtest=RealPaperQaTest#canonicalJson_chunkSampleForReview -Dchunker.qa.sample=true`

## 全局指标

```text
chunkCount  = 126
tokens      = avg 172.0 / p50 79 / p90 490 / max 685 / min 11
分类        = text 42 / list 6 / figure 3 / table 6 / formula 0 / footnote 0 / caption 1 / mixed 1 / abstract 1 / reference 66
异常        = oversized 0 / undersized 89 / empty 0 / noise 0 / crossSection 0 / splitParagraph 0
跳过        = figure 2 / table 0 / reference 0
referenceChunkRatio = 0.52   sectionCoverageRatio = 1.00
```

## 字段说明

- `embeddingText` 即**送入 embedding 的完整文本**：前两行是 `Document:` / `Section:` 上下文前缀，其后与 `content` 原文一致（无前缀时二者相同）。
- `tokenCount` **只统计 `content`**（不含前缀），因此实际喂给模型的 token 会略多于该值。
- `sourceBlockIds` 可回溯至 Marker 原始 block（如 `/page/3/Text/11`），用于定位原文与页码。
- `overlapBlockIds` 仅在 `rag.chunker.overlap-enabled=true` 时非空（本次为默认关闭）。

## 各类型检查要点

| 类型 | 检查要点 |
|---|---|
| TEXT | 自然段是否被拆断？跨页段是否为完整逻辑单元？`sectionPath` 归属是否正确？ |
| LIST | 所有 item 是否完整？有无被误当普通段落？ |
| MIXED | 短前置说明与紧随列表是否成对出现？ |
| FIGURE | caption 是否完整？有无 `imageKey`/base64 泄漏？ |
| TABLE | caption + 表头 + 数据行是否齐全？有无 html 泄漏？ |
| CAPTION | orphan caption 是否被保留、内容是否有意义？ |
| REFERENCE | 是否只含单条文献？有无 title/doi 缺失导致的空壳？ |
| ABSTRACT | 是否为完整摘要？ |

---

## [1] ABSTRACT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:__abstract__:0`

| 字段 | 值 |
|---|---|
| order | 0 |
| tokenCount / charCount | 328 / 1310 |
| 页码 | null - null |
| sectionLevel | null |
| sectionPath | （不属于任何 Section） |
| blockTypes | [] |
| sourceBlockIds |  |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `e33a617adc26beae` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers

Content:
Cardiovascular diseases are the leading cause of death worldwide. With electrocardiogram (ECG) machines becoming more accessible, passive monitoring for arrhythmia detection is now possible. This work highlights the importance of self-supervised learning in detecting arrhythmias by leveraging large-scale unlabelled ECG data to improve performance and reduce overƤtting to class imbalance and noise. We propose Masked Patch Modelling (MPM) and use 8.2 million unlabelled ECGs for selfsupervised pre-training, introducing PatchECG, a 1D Transformer model that can be Ƥne-tuned for various ECG tasks. PatchECG achieves state-of-the-art results on standard datasets, including PTB-XL multi-label classiƤcation, and sets new benchmarks on the largest and highest-quality multi-label dataset to date. Compared to existing methods, PatchECG is Ƥve times more computationally eƥcient while increasing model capacity by a factor of 14. We also compare the 1D PatchECG model to a stateof-the-art 2D vision Transformer, HeartBEiT, and observe signiƤcantly higher performance. Finally, ablation studies reveal a 2% performance improvement in handling class imbalance, label noise, and over-parameterization. These Ƥndings demonstrate the potential of self-supervised learning in advancing automated arrhythmia detection.
```

---

## [2] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/0/SectionHeader/3:0`

| 字段 | 值 |
|---|---|
| order | 1 |
| tokenCount / charCount | 478 / 1911 |
| 页码 | 1 - 1 |
| sectionLevel | 1 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/0/Text/7, /page/0/Text/8, /page/0/Text/9 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `06b1be67b0c58705` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers

Content:
According to the World Health Organization, Cardiovascular Disease (CVD) is the primary cause of death globally, accounting for 32% of all fatalities worldwide1 . e annual burden of CVDs on the European and American economies is estimated to be worth €210 billion and $555 billion respectively2,3 .

Population-based screening with the electrocardiogram (ECG) can detect CVDs early and help prevent lifethreatening complications. e most commonly used approach is the standard 12-lead ECG, a non-invasive form of monitoring. However, with more than 300 million ECGs obtained annually worldwide4 , manual monitoring is too labour-intensive and time-consuming to conduct in practice unless Articial Intelligence (AI) can help reduce the workload5 . With the rise of ambulatory ECG monitoring through the use of Holter monitors and consumer electronics (e.g., Apple watches, FitBits, etc.)6 , the number of ECGs collected will only increase as monitoring extends outside the clinical environment. However, this also presents an opportunity to perform realtime detection—which is not possible to perform manually—for time-critical events (e.g., Myocardial Ischemia) while also screening for episodic arrhythmias that occur sporadically (e.g., Paroxysmal Atrial Fibrillation).

Recent work in applying modern Deep Learning approaches to arrhythmia detection has yielded incredible results, outperforming trained cardiologists4 while doing so in milliseconds. However, these methods have proven brittle when generalized to real-world data, oen subject to various signal contaminants (e.g., motion artifact, baseline wander, etc.)7 . is can lead to critical failures such as misdiagnosis and "alarm fatigue", a condition where medical professionals will ignore potentially life-saving alarms as they become increasingly desensitized to false positives—a condition identied as one of the top health technology hazards7 .
```

---

## [3] FIGURE — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/0/SectionHeader/3:3`

| 字段 | 值 |
|---|---|
| order | 4 |
| tokenCount / charCount | 49 / 196 |
| 页码 | 2 - 2 |
| sectionLevel | 1 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers |
| blockTypes | [FIGURE] |
| sourceBlockIds | /page/1/Figure/6, /page/1/Caption/7 |
| overlapBlockIds | （无） |
| oversized / undersized | false / true |
| contentHash | `eb0bf3060c131f82` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers

Content:
Fig. 1. Comparison of original signal plotted as an image and the compressed version compatible with 2-dimensional ViTs. (a) Original: 1000 x 1000 resolution. (b) Compressed: 224 x 224 resolution.
```

---

## [4] LIST — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/0/SectionHeader/3:5`

| 字段 | 值 |
|---|---|
| order | 6 |
| tokenCount / charCount | 418 / 1672 |
| 页码 | 3 - 3 |
| sectionLevel | 1 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers |
| blockTypes | [LIST] |
| sourceBlockIds | /page/2/ListGroup/3 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `37cd24f547441e7e` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers

Content:
- We introduce a unied environment that aggregates the largest publicly available labelled and unlabelled ECG datasets (8.2M unlabeled samples, 268k labeled samples), creating a new benchmark for ne-tuning and enabling the identication of optimal 1-dimensional Transformer architectures and pre-training tasks.
- We propose the PatchECG network, a novel architecture tailored for ECG data, and adapt the Masked Autoencoding pre-text task from Vision Transformers (ViTs) to create a compute-ecient pre-training method. is approach allows us to train the largest publicly available ECG model to date (∼85 million parameters) with only 1/5 the resources, providing a foundational model for downstream tasks.
- We demonstrate the eectiveness of self-supervised learning for handling noisy data and achieving stateof-the-art (SOTA) results. Our ablation studies show that as the amount of data increases, the importance of specialized techniques diminishes. is observation aligns with ndings in previous studies21–23, which emphasize that scaling, compute and data is a critical factor in achieving generalizable and robust solutions in machine learning.
- By addressing critical challenges such as limited data, class imbalance, label noise, and over-parameterization, we achieve a performance improvement of over 2% with the PatchECG model. e model sets new benchmarks on large-scale datasets and achieves SOTA results on smaller datasets like PTB-XL.
- Comparative experiments conducted in this study are among the most comprehensive in the literature, including evaluations on new datasets and comparisons to SOTA methods. is provides a robust baseline for future research.
```

---

## [5] LIST — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/2/SectionHeader/4:0`

| 字段 | 值 |
|---|---|
| order | 7 |
| tokenCount / charCount | 189 / 753 |
| 页码 | 3 - 3 |
| sectionLevel | 2 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > ScientiƤc contributions |
| blockTypes | [LIST] |
| sourceBlockIds | /page/2/ListGroup/5 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `ac9f8e36084a1fb7` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > ScientiƤc contributions

Content:
- We provide the most comprehensive demonstration to date that 1D signal processing is both sucient and computationally superior (5× more ecient) to 2D approaches for large-scale ECG analysis, supporting and extending prior work that has advocated for direct signal processing.
- We provide empirical evidence that self-supervised learning scales eectively with unlabeled ECG data (8.2M samples), enabling the training of the largest publicly available ECG model (85M parameters).
- We introduce novel insights on the relationship between model capacity, implicit bias, and dataset size, showing that convolutional biases lead to premature saturation in large-scale settings while vanilla transformers continue to benet from increased pre-training data.
```

---

## [6] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/2/SectionHeader/6:1`

| 字段 | 值 |
|---|---|
| order | 9 |
| tokenCount / charCount | 42 / 166 |
| 页码 | 3 - 3 |
| sectionLevel | 3 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > ScientiƤc contributions > Clinical relevance |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/2/Text/8 |
| overlapBlockIds | （无） |
| oversized / undersized | false / true |
| contentHash | `6806f93fc2a7aa79` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > ScientiƤc contributions > Clinical relevance

Content:
ese contributions collectively establish a scalable, ecient, and generalizable framework with potential for real-world applications in automated arrhythmia detection.
```

---

## [7] MIXED — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/2/SectionHeader/10:0`

| 字段 | 值 |
|---|---|
| order | 10 |
| tokenCount / charCount | 431 / 1723 |
| 页码 | 3 - 3 |
| sectionLevel | 3 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Results > Tasks and metrics |
| blockTypes | [PARAGRAPH, LIST] |
| sourceBlockIds | /page/2/Text/11, /page/2/ListGroup/12 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `ba851009de95c527` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Results > Tasks and metrics

Content:
ere are three classication tasks which we will use to compare the performance of the models:

- 1. PTB-XL Dataset Multi-label Classication: e default testing environment will be the PTB-XL multi-label classication task on all 71 classes. is is currently the de facto dataset used to compare current SOTA models, owing mainly to the suggested stratication folds provided by the authors of the dataset14. Folds 1-8 are to be used for training, while fold 9 is reserved for validation and fold 10 for testing. Experiments are averaged over 10 trials.
- 2. Unied Dataset Multi-label Classication: e Chapman-Shaoxing and CinC-2020 datasets are combined to form the largest labelled dataset to date. To ensure balanced splits and prevent data leakage, Iterative Strati cation Shuing24,25 is employed. is method maintains the distribution of labels across the training, validation, and testing sets. Classes with fewer than 15 examples are excluded from the multi-label classication task, as training, validation, and testing folds cannot be generated otherwise. If a sample has no remaining labels aer this step, it is removed from the dataset. Otherwise, it is retained with its remaining labels. Chapman-Shaoxing contains one record per patient (45,152 unique patients), ensuring no patient-level leakage. For CinC-2020, over 85% of records originate from dierent patients. erefore, the combined Unied Dataset maintains strong inter-patient separation across train/validation/test splits, with an estimated >80% of records representing unique patients. Experiments are averaged over 10 trials to ensure robust evaluation, and all models are guaranteed to be trained and tested on the same splits to facilitate fair comparisons.
```

---

## [8] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/3/SectionHeader/6:0`

| 字段 | 值 |
|---|---|
| order | 13 |
| tokenCount / charCount | 125 / 498 |
| 页码 | 4 - 4 |
| sectionLevel | 2 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparative experiments |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/3/Text/7 |
| overlapBlockIds | （无） |
| oversized / undersized | false / true |
| contentHash | `754e651a146f5bd8` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparative experiments

Content:
is study presents one of the most comprehensive sets of comparative experiments in the literature, encompassing evaluations on new datasets and benchmarking against state-of-the-art methods, such as CPCbased frameworks (e.g., 4FC + 2LSTM + 2FC). While other promising frameworks like MAMba28 exist, their inclusion would likely necessitate a dedicated study. e primary objective here is to establish a robust baseline for future research rather than exhaustively exploring every emerging framework.
```

---

## [9] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/3/SectionHeader/8:0`

| 字段 | 值 |
|---|---|
| order | 14 |
| tokenCount / charCount | 577 / 2308 |
| 页码 | 4 - 5 |
| sectionLevel | 2 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparison of 1-dimensional transformers |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/3/Text/9, /page/3/Text/10, /page/3/Text/11, /page/4/Text/1 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `0f09f60c01bb0cc2` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparison of 1-dimensional transformers

Content:
is section compares three dierent 1-dimensional Transformer architectures to determine the best among them. PatchTST represents the current SOTA for time-series forecasting29. MLE-ViT is the current SOTA for ECG analysis9 , and the base ViT provides a baseline comparison. e results from the comparison are available in Table 1.

All Transformers are pre-trained on the Unied dataset for 500 epochs and ne-tuned on the PTB-XL dataset. Fine-tuning for all models follows the same standard 20 epochs of head-only ne-tuning before 70 epochs of full ne-tuning with layer-wise decay. e best model for each is selected according to validation loss. For each model, we follow the author's recommendations for hyper-parameter settings. e size of the models is kept relatively consistent. However, the best architecture for MLE-ViT is used according to the results from9 . Model dimensions for PatchTST and the base ViT (PatchECG small) are available in the Supplementary material section: hyperparameters, including Supplementary Tables S2, S3, S4 and S5.

We are most interested in the performance improvement from pre-training, and thus, the base ViT performs the best with an improvement in the AUROC score of nearly 10% from 0.8067 without pre-training to 0.9031 with pre-training. e MLE-ViT obtains the highest AUROC score of 0.9090; however, this is likely explained by the convolutional patch embeddings, which introduce an inductive bias to the model, enabling faster convergence and higher performance on less data. is hypothesis is supported by the observation that MLE-ViT shows only a 2% improvement from pre-training (0.8880 to 0.9090), while the vanilla ViT improves by 9.64%. is pattern suggests that convolutional embeddings provide benets in limited data environments, but may limit the model's capacity to fully leverage large-scale pre-training—a phenomenon consistent with prior observations that implicit biases can lead to premature saturation21. We investigate this further in the Supplementary material section: ablation studies, with results in Supplementary Table S1. While our evidence is comparative rather than denitive, the crossover pattern (MLE-ViT achieving higher baseline performance but smaller improvement from pre-training) provides suggestive evidence for this saturation eect.
```

---

## [10] TABLE — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/3/SectionHeader/8:1`

| 字段 | 值 |
|---|---|
| order | 15 |
| tokenCount / charCount | 92 / 365 |
| 页码 | 4 - 4 |
| sectionLevel | 2 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparison of 1-dimensional transformers |
| blockTypes | [TABLE] |
| sourceBlockIds | /page/3/Table/12, /page/3/Caption/13 |
| overlapBlockIds | （无） |
| oversized / undersized | false / true |
| contentHash | `7cbf973854549daa` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparison of 1-dimensional transformers

Content:
Table 1. Performance results from Transformer architecture comparison. Fine-tuning is performed on the multi-label (71 label) classication task from PTB-XL. e scores reported are the macro-AUROC score.
PTB-XL evaluation	
Method	Model		
		No pre-train	Pre-train
MPM	PatchTST	0.8687(20)	0.8827(10)
MPM	ViT	0.8067(12)	0.9031(14)
ECG-MAE 9	MLE-ViT	0.8880(20)	0.9090(11)
```

---

## [11] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/4/SectionHeader/3:0`

| 字段 | 值 |
|---|---|
| order | 17 |
| tokenCount / charCount | 685 / 2740 |
| 页码 | 5 - 5 |
| sectionLevel | 3 |
| sectionPath | Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparison of 1-dimensional transformers > PTB-XL multi-label classiƤcation |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/4/Text/4, /page/4/Text/5, /page/4/Text/6, /page/4/Text/7 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `bc6cdcceac41e956` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers > Comparison of 1-dimensional transformers > PTB-XL multi-label classiƤcation

Content:
is section compares the PatchECG model and the current SOTA on the PTB-XL multi-label classication task, highlighting the PatchECG model's competitive performance on small datasets compared to models predisposed to implicit biases. e current SOTA for multi-label classication is the LSTM pre-trained using Contrastive Predictive Coding (CPC). is is a form of self-supervised learning known as instance discrimination where the model is pre-trained to align its predictions in latent space with positive samples and to distance its predictions from negative samples. e positive samples are selected as the 12 time-steps (0.12 seconds) into the future, while the 128 negative samples are drawn from dierent parts of the same sequence.

is section also emphasizes the importance of self-supervised pre-training by comparing head-only and fully ne-tuned models with and without self-supervised pre-training. All results are available in Table 2. A comparison between the LSTM and PatchECG models should be drawn between both pre-trained on Ribeiro-B. However, the results from the original paper27 with the LSTM pre-trained on the Unied dataset are also included. We refrain from comparing models that may include data leakage.

When comparing the performance of the best LSTM model (0.9401) to the best PatchECG model (0.9385), the LSTM is superior by less than 0.2%. ese results show a marginal dierence and become even closer when considering the LSTM model pre-trained on Ribeiro-B (0.9391) with less than 0.1% dierence. e slight superiority of the LSTM can likely be explained by its inherent temporal biases, which aid convergence in this low-data environment. is hypothesis is further supported by the exceptional performance of the LSTM with only supervised training. Notably, the dierence between the AUROC score of the LSTM pre-trained on Ribeiro-B (0.9391) and the LSTM model with no pre-training (0.9318) is approximately 0.75%. e dierence between the PatchECG model pre-trained on Ribeiro-B (0.9385), and the supervised-only model (0.8889) is much greater at a dierence of approximately 5%.

is helps to emphasize the minimal implicit bias to which Transformers are pre-disposed, especially in comparison to more data-ecient models. It also serves to demonstrate the considerable benet that selfsupervised training has had on the PatchECG model. Moreover, the results from the linear analysis, where a linear head is attached to the frozen weights of the base model and ne-tuned, provide further insight into the strong representations learned by the PatchECG model. e PatchECG model's linear evaluation score of 0.8931 vs the LSTM's score of 0.8175 shows the power of its learned representations with a dierence of more than 7.5%.
```

---

## [12] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/7/SectionHeader/11:0`

| 字段 | 值 |
|---|---|
| order | 32 |
| tokenCount / charCount | 318 / 1270 |
| 页码 | 8 - 9 |
| sectionLevel | 2 |
| sectionPath | Discussion |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/7/Text/12, /page/8/Text/5, /page/8/Text/6 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `b2fc39ff8ec47e22` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Discussion

Content:
Our results demonstrate that 1D signal processing is both sucient and computationally superior to 2D imagebased approaches for ECG analysis. MPM directly preserves the temporal continuity and precise voltage amplitudes of ECG signals, which are critical diagnostic features. In contrast, 2D approaches (as shown in Fig. 1) introduce compression artifacts that destroy ne-grained morphological details like ST-segment elevation, T-wave inversions, and QRS complex variations—features essential for clinical diagnosis.

Our patch-based approach (50 sample patches at 100Hz = 500ms windows) aligns with clinically relevant timescales: P-waves (80-120ms), QRS complexes (80-120ms), and T-waves (120-160ms). is enables the model to learn physiologically meaningful temporal relationships without the information loss inherent in imagebased representations. e strong performance on STEMI detection (AUPRC=0.78, AUROC=0.975) provides evidence of clinically meaningful feature learning, as STEMI diagnosis requires recognition of specic temporal patterns (ST-elevation lasting >20ms in 2 or more contiguous leads). Additionally, the model achieves 94% AUROC by epoch 2 during ne-tuning, suggesting the pre-trained representations already encode diagnostically relevant patterns.
```

---

## [13] CAPTION — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/7/SectionHeader/11:1`

| 字段 | 值 |
|---|---|
| order | 33 |
| tokenCount / charCount | 53 / 212 |
| 页码 | 9 - 9 |
| sectionLevel | 2 |
| sectionPath | Discussion |
| blockTypes | [CAPTION] |
| sourceBlockIds | /page/8/Caption/4 |
| overlapBlockIds | （无） |
| oversized / undersized | false / true |
| contentHash | `c2304bca0c3b455a` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Discussion

Content:
Fig. 3. AUROC score plotted against average label condence for an entire label class. Pearson Correlation(r) = 0.17, p-value = 5.95e−20 &lt; 0.0005. Spearman Correlation(rs) = 0.42, p-value = 4.49e−4 &lt; 0.0005.
```

---

## [14] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/9/SectionHeader/2:0`

| 字段 | 值 |
|---|---|
| order | 35 |
| tokenCount / charCount | 419 / 1674 |
| 页码 | 10 - 10 |
| sectionLevel | 2 |
| sectionPath | Discussion > Generalizability across architectures and datasets |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/9/Text/3, /page/9/Text/4, /page/9/Text/5 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `ce6399204f66f51e` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Discussion > Generalizability across architectures and datasets

Content:
e novelty of this work lies in demonstrating the eectiveness of self-supervised learning with large-scale unlabelled data for 1-dimensional ECG signal processing. Specically, we prove that 1-dimensional signals are sucient and computationally more ecient compared to 2-dimensional approaches, avoiding unnecessary complexity and information loss. is study represents the largest-scale pre-training eort to date using 1-dimensional data and publicly available datasets, setting a new benchmark for future research. By introducing PatchECG and leveraging Masked Patch Modelling, we provide a scalable and ecient solution tailored to ECGspecic challenges, emphasizing generalization and large-scale pre-training.

Our architecture comparison study demonstrates that MPM pre-training generalizes across dierent model families: PatchTST (SOTA for time-series) improves by 1.4%, base ViT improves by 9.64%, and MLE-ViT (SOTA for ECG) improves by 2.1%. All three architectures benet from MPM pre-training, though the magnitude diers. e larger improvement for base ViT (9.64%) vs MLE-ViT (2.1%) supports our hypothesis that implicit biases limit the capacity to learn from large-scale pre-training.

Our evaluation spans multiple datasets with dierent patient populations and recording equipment, providing evidence of cross-dataset generalization: pre-training on Ribeiro-B (Brazilian population), ne-tuning and evaluating on PTB-XL (German population), Chapman-Shaoxing (Chinese population), and CinC-2020 (mixed American and European populations). e tight condence intervals across datasets (±0.007 on PTB-XL, ±0.003 on Unied) further demonstrate robustness to distribution shis.
```

---

## [15] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/11/SectionHeader/4:0`

| 字段 | 值 |
|---|---|
| order | 43 |
| tokenCount / charCount | 338 / 1349 |
| 页码 | 12 - 12 |
| sectionLevel | 2 |
| sectionPath | Discussion > Pre-processing |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/11/Text/5, /page/11/Text/6 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `921580656ea74be1` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Discussion > Pre-processing

Content:
e following modications to the datasets in Table 6 should be noted. 268,812 samples from the Ribeiro dataset are omitted as these samples are annotated. erefore, they are not used for pre-training but are reserved for other tasks. We refer to the labelled subset of Ribeiro as Ribeiro-A and the unlabelled subset as Ribeiro-B. e St.Petersburg Incart dataset was removed from CinC-2020 to maintain the quality of the dataset, as the recordings are 30 minutes long, and annotations are applied to the entire record. is is referred to as "inexact"or "imprecise"labelling; however, annotations applied to each segment or smaller segments of 1 minute are preferred.

All data is re-sampled to 100Hz and 10-second time samples. If a signal is too short, it is equally padded with zeros on both sides of the original signal. However, any signal shorter than 7 seconds is discarded as the shortest signals consistently recorded by the Ribeiro dataset are between 6 and 7 seconds, guaranteeing the model has been pre-trained on signals of this length. If signals are longer than 10 seconds, they are automatically split into 10-second segments, with the nal segment either being zero-padded or discarded depending on its length. Finally, all segmented signals are given the same label as the original signal in accordance with the imprecise labelling regime.
```

---

## [16] FIGURE — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/12/SectionHeader/4:1`

| 字段 | 值 |
|---|---|
| order | 49 |
| tokenCount / charCount | 447 / 1785 |
| 页码 | 13 - 13 |
| sectionLevel | 3 |
| sectionPath | Discussion > Pre-processing > Baseline models |
| blockTypes | [FIGURE] |
| sourceBlockIds | /page/12/Diagram/1, /page/12/Caption/2 |
| overlapBlockIds | （无） |
| oversized / undersized | false / false |
| contentHash | `48d7fdba9542e316` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Discussion > Pre-processing > Baseline models

Content:
Fig. 4. Overview of PatchECG and the Masked Patch Modelling approach for ECG data. (Only 6 of the original 12 leads are shown due to size constraints). (1) e original ECG signal is divided into patches of length 50 by default, enabling ecient processing of 1-dimensional data. is avoids the unnecessary complexity and information loss associated with 2-dimensional approaches, proving that 1-dimensional signals are sucient and computationally more ecient. (2) A novel masking strategy is applied, where 40% of the patches are masked (red) to encourage the model to learn robust representations, while the remaining patches are le unmasked (blue). is strategy is specically designed to handle the unique challenges of ECG data, such as noise, class imbalance, and over-parameterization. (3) Patches are linearly projected to the model dimension dmodel using a linear embedding, and masked patches are replaced with a learnable mask token [M]. (4) e Transformer backbone processes the input, and the task-specic MLP g reconstructs the corrupted patches, demonstrating the model's ability to handle noisy and incomplete data while preserving critical temporal patterns. (5) e entire signal is reconstructed, with early and late reconstructions illustrating the model's progress in learning meaningful representations. (6) e pre-trained backbone network is netuned for downstream tasks by replacing the pre-training head g with a task-specic MLP f, showcasing the scalability, adaptability, and computational eciency of the PatchECG model for various ECG tasks. e proposed method achieves state-of-the-art results while being ve times more computationally ecient than existing methods, enabling the training of the largest publicly available ECG model to date with ∼85 million parameters.
```

---

## [17] TEXT — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:/page/16/SectionHeader/7:0`

| 字段 | 值 |
|---|---|
| order | 59 |
| tokenCount / charCount | 11 / 41 |
| 页码 | 17 - 17 |
| sectionLevel | 1 |
| sectionPath | Competing interests |
| blockTypes | [PARAGRAPH] |
| sourceBlockIds | /page/16/Text/8 |
| overlapBlockIds | （无） |
| oversized / undersized | false / true |
| contentHash | `62c2770bc9b4ea40` |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers
Section: Competing interests

Content:
e authors declare no competing interests.
```

---

## [18] REFERENCE — `Toward_robust_automated_cardiovascular_arrhythmia_detection_using_self-supervised_learning_and_1-dimensional_vision_transformers.json:__references__:0`

| 字段 | 值 |
|---|---|
| order | 60 |
| tokenCount / charCount | 49 / 194 |
| 页码 | null - null |
| sectionLevel | null |
| sectionPath | （不属于任何 Section） |
| blockTypes | [] |
| sourceBlockIds |  |
| overlapBlockIds | （无） |
| oversized / undersized | false / true |
| contentHash | `781b027ef82b37bf` |
| referenceIds | ref-1 |

**embeddingText**

```text
Document: Toward robust automated cardiovascular arrhythmia detection using self-supervised learning and 1-dimensional vision transformers

Content:
1. Kaptoge, S. et al. World health organization cardiovascular disease risk charts: revised models to estimate risk in 21 global regions. e Lancet global health 7, e1332–e1345 (2019).
Year: 2019
```

---

