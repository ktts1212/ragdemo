# 主章节锚点保守重挂（Methods root）+ 无编号同 rawLevel 父子对诊断

> 上轮报告：`层级纠偏_rawLevel与编号同级修复.md`、`结构修复_浮动归属与锚点保留.md`
> 依据 Skill：`src/main/resources/skills/canonical_section_float_repair_skill.md` §4 / §5.3
> 对应代码：`src/main/java/com/example/ragdemo/document/cleaner/`

本轮完成三件事：

```text
(1) 实现保守的 major-anchor reparenting  → Methods root 已解决
(2) 生成【无编号 · rawLevel 相同却父子】诊断报告  → 只诊断、不自动修
(3) §5.3 不实现 —— 等人工看完 (2) 的候选对再决定
```

---

# (1) MajorAnchorReparenting：解决 Methods root

## 1.1 要解决的形态

```text
修复前（目标论文）：
L1 Discussion
  L2 （Discussion 自己的子节）...
  L2 Methods            ← 锚点、无 block 无子节点
  L2 Datasets           ← Marker 把三者标成同一 depth（rawLevel=2/2/2）
  L2 Pre-processing
    L3 Patch segmentation strategy ...

修复后：
L1 Discussion
  L2 （Discussion 自己的子节）...
L1 Methods              ← 提升为 root
  L2 Datasets           ← 收编
  L2 Pre-processing     ← 收编（其 8 个 L3 子节完整保留）
L1 Data availability ...
```

## 1.2 为什么需要「收编」

只把空的 `Methods` 提为 root 会留下一个**无内容的空 root**，
`Datasets` 的 `sectionPath` 仍是 `Discussion > Datasets` —— 问题并未解决。
因此重挂 = **提升为 root** + **收编其后连续同级**。

## 1.3 保守触发条件（必须**全部**满足）

```text
1. M.title 命中主章节锚点（严格归一化精确匹配）
2. M 无 block 且无子节点  ← "Marker 把其子节拉平成同级"的典型签名
3. M 的父 P 存在，且 P 也是主章节锚点（= Validator 的
   MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING）
4. M 与 P 都有 Marker rawLevel  ← 必须有 Marker 佐证
5. 存在可收编对象：M 之后【连续】的同级 sibling，满足
   rawLevel == M.rawLevel 且标题不是主章节锚点
```

**收编边界由两条独立证据共同界定**：

```text
· 语义边界：遇到下一个主章节锚点标题即停
· Marker 佐证：rawLevel 必须与 M 相同，不同即停
```

任一条不满足即**不动** —— 包括「锚点子节带正文」的情况（证据不足，留给人工/后续）。

## 1.4 真实效果

```text
[Methods] 由 Discussion 的子节提升为 root
  收编 = [Datasets, Pre-processing]
  rawLevel = 1/2（Discussion / Methods）
  level 2 → 1      path [Discussion, Methods] → [Methods]
```

| 论文 | reparented | 剩余锚点嵌套 |
|---|---|---|
| Hierarchical / JAMC / Poly-Window / LEARNING GENERAL | 0 / 0 / 0 / 0 | 0 |
| **Toward robust** | **1** | **0** |
| 合计 | 1 | 0 |

→ `MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING` 指标 **1 → 0**，其余 4 篇 **0 误伤**。

## 1.5 单测（9 个，`MajorAnchorReparentingTest`）

```text
✅ 应修：空锚点被错嵌 → 提升为 root 并收编 Datasets / Pre-processing
✅ 应修：重挂后重建被收编子树的 level / path
✅ 应修：收编遇到下一个主章节锚点标题即停（Conclusions 之后的 Appendix 不受影响）
✅ 应修：收编在 rawLevel 不再相同时停止
❌ 不修：锚点子节带正文（非空孤儿）
❌ 不修：父节点不是主章节锚点
❌ 不修：没有可收编同级（不产出空 root）
❌ 不修：缺少 Marker rawLevel 佐证
❌ 不修：已是 root
```

---

# 1.6 顺带修正：编号解析缺口（尾部句点）

生成诊断报告时发现 `4.4.6. Ablations on ...`（**带尾部句点**）未被识别为编号标题 ——
`HeadingNumbering` 与解析期 `MarkerSectionBuilder.levelFromNumbering` 用的是同一套正则，都有此缺口。

修正：`^\d+(\.\d+)+\\.?\s+`（尾部句点可选）并按数字段数算深度（`4.4.6.` → 3，而非 4）。

**这导致 JAMC 新增 2 处正确提级**（已逐条核对）：

```text
4.2. Evaluation metrics  >  4.3. Implementation details
    编号 4.2 / 4.3 同级 → 提级 ✅（rawLevel 2/3 冲突=true，按编号裁决）
4.4.2. Ablations on ??   >  4.4.3. Ablations on ??
    编号 4.4.2 / 4.4.3 同级 → 提级 ✅（同上）
```

→ §5.2 累计提级 **9 处**（LEARNING GENERAL 7 + JAMC 2），5 篇合计 **0 误修**。

---

# (2) 无编号 · rawLevel 相同却父子 —— 诊断报告（不自动修）

**报告文件**：`src/test/output/reports/无编号同rawLevel父子对_诊断.md`

```text
命令：mvn test -Dtest=RealPaperQaTest#unnumberedRawLevelPairReport_forReview -Dchunker.qa.pairReport=true
候选 pair 总数：7     其中「子节点自身有正文」：4
```

## 2.1 为什么不自动修

`rawLevel` 相同**不是**可靠的同级信号 —— 实测中大量 pair 是合法父子，例如
`raw=2/2  "3 Method" > "3.1 Event Reconstruction"`（编号 3 / 3.1 证明真父子）。
因此凡**无编号**的这类 pair，只列出、不修改。

## 2.2 全部 7 个候选（人工判定材料）

### 模式 A：`<论文标题 root>` 的子节，且 `rawLevel` 都等于 1（5 例）

| 论文 | 父 | 子 | rawLevel | treeLevel | 子自身内容 | 子的子节点 | 前一个 sibling |
|---|---|---|---|---|---|---|---|
| LEARNING GENERAL | 论文标题 | `A Additional Experiments` | 1/1 | 1/2 | 0 块 / 0 字符 | 5 | 4 Experimental Settings |
| LEARNING GENERAL | 论文标题 | `B Ablation Study` | 1/1 | 1/2 | 0 块 / 0 字符 | 3 | A Additional Experiments |
| LEARNING GENERAL | 论文标题 | `E Case Analysis of UMAP Embeddings` | 1/1 | 1/2 | 3 块 / 1223 字符 | 3 | B Ablation Study |
| Toward robust | 论文标题 | `Comparison of 1-dimensional transformers` | 1/1 | 1/2 | 5 块 / 2562 字符 | 3 | Comparative experiments |
| Toward robust | 论文标题 | `Investigation of results` | 1/1 | 1/2 | 0 块 / 0 字符 | 2 | Comparison of 1-dimensional transformers |

**共同形态**：Marker 说这些是 **一级标题**（rawLevel 1，与论文标题 root 同级），
但最终树把它们放成了论文标题 root 的子节（treeLevel 2）。

**注意（已更正的重要认知）**：Toward robust 的 2 例**并非缺陷** ——
`SectionHierarchyNormalizer.repairRootLevelDrop` 实现的正是 skill `marker_cleaner_final_polish_skill.md`
§5.4 **Rule A（level 突然掉到 1）**，而该规则文档里的**示例恰恰就是 `Comparison of 1-dimensional transformers`**：

```text
### Rule A：level 突然掉到 1
如果某 section level == 1，但满足：
  前面仍处于某个主章节上下文 / 不属于主章节白名单 / 其后多个 section 原本都是其 level 2 children
则将其降为当前主章节下的 subsection。

示例（skill §5.4 原文）：
Comparison of 1-dimensional transformers   ← Marker level 1
PTB-XL multi-label classification          level 2
Unified multi-label classification         level 2
STEMI binary classification                level 2
```

即：`rawLevel == 1` 在 Marker 这里**本身就是错的**，Rule A 明确要求覆盖它。
**因此「用 rawLevel 阻止重挂」的规则会直接破坏 Rule A 的文档化行为，已撤回。**

### 模式 B：我们自己 `Methods` 重挂的产物（2 例，**不是错误**）

| 父 | 子 | rawLevel | treeLevel | 说明 |
|---|---|---|---|---|
| `Methods` | `Datasets` | 2/2 | 1/2 | 期望结构就是 `Methods > Datasets`；rawLevel 相同的假阳性 |
| `Methods` | `Pre-processing` | 2/2 | 1/2 | 同上（rawLevel 粒度粗，不足为据） |

## 2.3 修正后的读法

```text
这批"候选"绝大多数【不是缺陷】，而是 skill §5.4 Rule A 的预期产物：
  · 非白名单标题 + level 1 + 带 level 2 子节点 → 重挂为 subsection（设计如此）
  · 因此【不能】用 rawLevel == 1 反向阻止重挂（会破坏 Rule A 自身示例）

真正值得修的是 Rule A 的【白名单完备性】—— 下列 section 语义上明确是主章节，
却因标题未被识别而被误重挂：

  · 罗马前缀（3 例）：II. METHODS / III. RESULTS / IV. CONCLUSIONS
        归一化未剥离罗马前缀（"iii. results" ≠ "results"）→ 属明确 bug，修复无歧义

  · 标题变体（4 例）：
        2. Related works            （白名单是 "related work"）
        4. Experiments and results  （白名单是 "experiments" / "results"）
        5. Discussion and conclusion（白名单是 "discussion" / "conclusion"）
        4 Experimental Settings     （白名单是 "experimental setup"）
        → 可靠修复需"标题变体 / 复合标题"判定；skill §4.2 明确禁止
          "包含即算主章节"的宽松匹配，因此风险较高，需人工决策

模式 B（Methods > Datasets / Pre-processing）仍是假阳性，不写规则。
```

---

# (3) §5.3 未实现（按你的要求）

本轮**没有**实现任何无编号同级修复。等你看完上面 7 个候选对、给出结论后，
再决定是否写 §5.3 规则以及按哪个方向（2.3 的 (i) / (ii) / 都不写）。

---

# 验证

```text
全量              211 tests / 0 failures / 0 errors / 13 skipped     BUILD SUCCESS
新增单测          MajorAnchorReparentingTest  9 个
§9 指标           MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING  1 → 0
                  sameDepthHeadingParentChildConflictCount  全 5 篇 → 0
§5.2 累计提级     9 处（LEARNING 7 + JAMC 2），0 误修
上轮成果保持      Methods 标题保留；Table 3 → Unified multi-label classification
§27 六 Case       仍全绿（crossSection=0、tableChunks=6≥6、跨页段落同一 chunk）
```

**目标论文最终 section 树（节选）**

```text
L1 <论文标题>
  L2 Scientific contributions → L3 Clinical relevance
  L2 Results → L3 Tasks and metrics
  L2 Comparative experiments
  L2 Comparison of 1-dimensional transformers
    L3 PTB-XL multi-label classification
    L3 Unified multi-label classification
    L3 STEMI binary classification
  L2 Investigation of results
    L3 Model efficiency comparison
    L3 Noise in the label set
L1 Discussion
  L2 "" / Generalizability / Comparison with multi-modal / Relationship to masked...
L1 Methods                       ← 本轮成果
  L2 Datasets
  L2 Pre-processing
    L3 Patch segmentation strategy / Voltage standardization / Outlier handling /
       Baseline models / PatchECG / SSRL / MPM / LoRA
L1 Data availability / Code availability / Author contributions / Funding / Competing interests
```

---

# 文件清单

```text
新增  src/main/java/.../document/cleaner/SectionTreeUtils.java          (树通用工具，去重复)
新增  src/main/java/.../document/cleaner/MajorAnchorReparenting.java    (锚点保守重挂 + 审计)
改    src/main/java/.../document/cleaner/HeadingNumbering.java          (补尾部句点：4.4.6.)
改    src/main/java/.../document/cleaner/SectionHierarchyRepair.java    (改用 SectionTreeUtils)
改    src/main/java/.../document/cleaner/PaperCleaner.java              (第 10 步接入)
新增  src/test/java/.../document/cleaner/MajorAnchorReparentingTest.java (9 tests)
改    src/test/java/.../document/pipeline/RealPaperQaTest.java          (+无编号 pair 诊断报告)
改    4 处 PaperCleaner 构造点
重生成 src/test/output/canonical/*                                       (5 篇)
新增  src/test/output/reports/无编号同rawLevel父子对_诊断.md            (本文第 (2) 项产物)
新增  src/test/output/reports/主章节锚点重挂与无编号同rawLevel诊断.md    (本文)
```

---

# 如何运行

```powershell
mvn test

# 锚点重挂单测
mvn test "-Dtest=MajorAnchorReparentingTest"

# 重生成 canonical（走新管道）
mvn test "-Dtest=RealPaperQaTest#rawJsonDir_cleanOnly_savesOutput" "-Dmarker.qa.rawDir=src/test/output/marker-raw"

# 生成【无编号 · rawLevel 相同却父子】诊断报告
mvn test "-Dtest=RealPaperQaTest#unnumberedRawLevelPairReport_forReview" "-Dchunker.qa.pairReport=true"

# 层级证据采集（rawLevel vs 最终树 / 编号冲突 / 同 rawLevel 却父子，stdout）
mvn test "-Dtest=RealPaperQaTest#hierarchyDiagnostic_forReview" "-Dchunker.qa.hierarchyDiag=true"
```
