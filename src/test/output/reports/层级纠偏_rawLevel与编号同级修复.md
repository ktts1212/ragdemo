# 层级纠偏：Marker rawLevel 保留（②C）+ 编号同深度同级修复（§5.2）

> 依据 Skill：`src/main/resources/skills/canonical_section_float_repair_skill.md` §5.2
> 上游报告：`结构修复_浮动归属与锚点保留.md`
> 对应代码：`src/main/java/com/example/ragdemo/document/cleaner/`

---

## 一、②C：保留 Marker 原始 heading depth

### 目标（明确定义）

把 Marker 在 `section_hierarchy` 中**声明的 heading depth** 保留下来，

```text
用作层级纠偏的【独立佐证】与审计信息
```

**②C 不承担修 `Methods` root** —— 那是另一件需要单独设计的保守 major-anchor reparenting 工作，
待 rawLevel / sibling repair 稳定后再实现（本轮**未做**，见 §七）。

### 实现

| 位置 | 改动 |
|---|---|
| `Section` | 新增字段 `Integer rawLevel`（非 Marker 来源如 GROBID / 测试构造为 `null`，表示"无 Marker 佐证"） |
| `MarkerSectionBuilder.openSection` | 解析期写入 `rawLevel = section_hierarchy.size()`（缺失为 null） |

### 与 `level` 的区别（这是它有价值的原因）

```text
level     解析期会被"标题编号推断"覆盖（resolveDepth 优先编号）
          解析后又被 Cleaner 按真实嵌套重算（enforceLevelPathConsistency: level = path.size()）
          → 它是"当前树的深度"，不含 Marker 的独立意见
rawLevel  保留 Marker 的原始声明；所有 Cleaner 写路径（normalizer / repair / reassign 等）
          只改 level / path，【不触碰 rawLevel】
```

---

## 二、关键证据：rawLevel 不能作为"应同级"的正向信号

实现 §5.2 前先采集了 5 篇论文的证据，结论**推翻了"rawLevel 相同 ⇒ 应同级"的直觉**：

```text
[SAME_RAW_NESTED] raw=2/2   3 Method         >  3.1 Event Reconstruction   ← 合法父子（编号 3 vs 3.1 证明）
[SAME_RAW_NESTED] raw=2/2   5 Experiments    >  5.1 Pretraining            ← 合法父子
[SAME_RAW_NESTED] raw=2/2   7 Conclusion     >  A Hyperparameters          ← 合法父子
[SAME_RAW_NESTED] raw=3/3   5.3 Downstream Tasks > 5.3.1 Benchmark Methodology ← 合法父子
```

| 论文 | rawLevel 相同却父子（例） | 其中真错误 |
|---|---|---|
| Hierarchical | 12 | 0 |
| JAMC | 14 | 0 |
| Poly-Window | 8 | 0 |
| LEARNING GENERAL | 11 | 7 |
| Toward robust | 2 | 0 |

**结论**：`rawLevel` 相同绝大多数是**合法父子** —— Marker 的 `section_hierarchy` 粒度较粗，
常把"节"与其"子节"标成同一 depth。若用它做提级信号会造成**大规模错误重排**
（skill §17 明确把"错误重排"排在"保守不修"之后）。

**可靠信号是"标题编号深度"**：

```text
[NUMBERED_CONFLICT] num=3/3  A.1 Pretraining ...  >  A.2 Effect ...   ← 真错误（A.1/A.2 应同级）
```

因此最终设计：**编号决定是否提级，`rawLevel` 仅作审计佐证 + 观测告警**。

---

## 三、§5.2：编号同深度同级修复

### 规则（`SectionHierarchyRepair`）

```text
仅当【父子标题都有编号】且【编号深度完全相同】时，把子节点提升为父节点的同级
错误：  2.1 Pre-processing
          └── 2.2 Baseline models
修正：  2.1 Pre-processing
        2.2 Baseline models
```

提级操作：`parent.children.remove(child)` → 插入到 `parent` 之后（保持文档顺序）→
**重建该子树（含全部后代）的 `level` 与 `path`**。

### 保守边界（skill §17：正确归属 > 保守不修 > 错误重排）

```text
✅ 提级：父子都有编号 且 深度相同
❌ 不动：无编号标题（属 skill §5.3，需更多结构证据）
❌ 不动：编号深度不同（如 3 Method > 3.1 Event Reconstruction —— 合法父子）
❌ 不动：子编号深度更浅（应更浅，但方向不确定，仅由 Validator 指标观测，避免过度重排）
❌ 不因 rawLevel 相同而提级（见 §二）
```

每轮只做**一次**提级，随后重新扫描以暴露新冲突（上限 100 轮，防病态输入不收敛）。

### 新增 `HeadingNumbering`（共用编号解析）

支持 `2` / `2.1` / `2.1.1`（点数）、`A.1`（附录小节 → 3）、`A.`（字母子节 → 2）、
`II.` / `III.`（罗马数字 → 1），并含**单字母罗马歧义消解**：

```text
"C." / "I." / "V." 既是罗马数字也是字母子节
→ 仅当其后标题文本【全为大写】时才判为罗马数字（一级），否则判为字母子节（二级）
（与解析期 MarkerSectionBuilder.levelFromNumbering 保持一致）
```

`CanonicalDocumentValidator` 的 `numberingDepth` 改为委托 `HeadingNumbering`，消除重复定义。

### 接入点

```text
... BackMatterClassifier
    SectionHierarchyNormalizer
    SectionHierarchyRepair          ← 新增（第 9 步）
    FloatBlockSectionReassigner
    EmptyContentCleaner
    CanonicalDocumentValidator
```

顺序依据 skill §7：**先稳定 Section 树，再做 Figure/Table 归属**。

---

## 四、顺带修正：上一轮 §9 指标的一个假阳性

上一轮我在 `CanonicalDocumentValidator` 里手写的编号解析**把单字母 `C.` 误判为罗马数字**，
导致 Poly-Window 报出 2 个不存在的 `SAME_DEPTH_HEADING_PARENT_CHILD_CONFLICT`：

```text
II. METHODS  >  C. Dataset          → 旧: num=1/1 → 误报冲突
                                    → 新: num=1/2 → 合法父子 ✅
```

改用 `HeadingNumbering` 后，Poly-Window 的该指标 **2 → 0**，且实际提级数 0（正确）。

---

## 五、真实数据结果

| 论文 | §5.2 提级数 | rawLevel 相同却父子（仅告警） | 修复后 `sameDepthHeadingParentChildConflictCount` |
|---|---|---|---|
| Hierarchical Self-Supervised | 0 | 12 | **0** |
| JAMC | 2（编号解析补齐尾部句点后新增，见后续报告） | 14 | **0** |
| Poly-Window | 0（原报 2 为假阳性） | 8 | **0** |
| **LEARNING GENERAL** | **7** | 11 | **0** |
| Toward robust | 0 | 2 | **0** |
| **合计** | **9** | 47 | **0** |

### LEARNING GENERAL 的 7 条提级（全部为真错误）

```text
[/page/12/SectionHeader/6] A.2 Effect of Learning Rates on Downstream Tasks
    oldLevel=4 → newLevel=3      rawLevel=2/3（冲突=true）
    path [..., A Additional Experiments, A.1 Pretraining..., A.2 ...]
      →  [..., A Additional Experiments, A.2 ...]                       ✅ A.1/A.2 成同级
[/page/13/SectionHeader/2] A.3 Nearest Neighbor Classifier          → 同级 ✅
[/page/13/SectionHeader/7] A.4 CroPA's Effect: Statistical Significance Analysis → 同级 ✅
[/page/13/SectionHeader/12] A.5 Visualization of ECG Representations → 同级 ✅
[/page/15/SectionHeader/4] B.2 Comparison with 12-Lead Model        → 同级 ✅
[/page/18/SectionHeader/6] E.2 AFib Samples in NSR Clusters         → 同级 ✅（rawLevel 2/2，无冲突）
[/page/18/SectionHeader/8] E.3 Implications of Atypical Cases       → 同级 ✅（rawLevel 2/2，无冲突）
```

注意 `rawLevel 冲突=true` 的 5 条：Marker 认为它们是父子（rawLevel 2/3），
但编号 `A.1` / `A.2` 明确表明同级 → **按编号提级**，并把冲突记录进审计，
便于人工复核"编号 vs Marker 层级"谁对。

---

## 六、验证

```text
单测
  SectionHierarchyRepairTest    9 个（新增，覆盖 skill §11 全部 Case）
    · 编号同深度（2.1/2.2、A.1/A.2）→ 提级
    · root 下的同深度父子 → 提为 root
    · 提级后重建整棵子树 level/path
    · 无编号真实父子（Methods > Pre-processing）不得误修
    · 编号深度不同的真实父子（3 Method > 3.1 ...）不得误修
    · 单字母罗马歧义（II. METHODS > C. Dataset）不得误修
    · rawLevel 相同但无编号 → 只告警不提级
    · rawLevel 与编号冲突 → 按编号提级 + 记为 rawLevelConflict

全量
  201 tests / 0 failures / 0 errors / 12 skipped        BUILD SUCCESS

指标回归
  sameDepthHeadingParentChildConflictCount：5 篇全部 → 0
  §27 六 Case 仍全绿（crossSection=0、tableChunks=6≥6、跨页段落同一 chunk）
  Table 3 → Unified multi-label classification（上一轮成果保持）
  Methods 标题仍被保留（上一轮 ②A 成果保持），sections=36
```

---

## 七、仍未修（明确不声称）

| # | 未修项 | 现状 | 前置条件 |
|---|---|---|---|
| 1 | ~~**`Methods` 仍是 `Discussion` 的子节点**~~ | ✅ **已修复** | 见 `主章节锚点重挂与无编号同rawLevel诊断.md`：Methods 由 `MajorAnchorReparenting` 提升为 root 并收编 `Datasets` / `Pre-processing`；`MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING` 由 1 → 0，其余 4 篇 0 误伤 |
| 2 | `Pre-processing` 父子化 `Baseline models`/`PatchECG`/`SSRL`/`MPM`/`LoRA` | 未修 | 这些标题**无编号** → 属 skill §5.3（无编号同级纠偏），需 3~4 条结构证据；本轮不做 |
| 3 | `Scientific contributions` / `Clinical relevance` 应同级 | 未修 | 同 #2 |
| 4 | skill §5.3 无编号同级纠偏 | 未实现 | 需更多可用结构证据（本轮已确认 `rawLevel` 不足以承担） |

**rawLevel 的用途边界（明确）**：本轮它被用于

```text
1. 审计记录（parentRawLevel / childRawLevel / rawLevelConflict）→ 人工复核"编号 vs Marker 层级"
2. 聚合观测告警 SAME_RAW_LEVEL_BUT_NESTED
【不用于】驱动任何自动移动
```

---

## 八、文件清单

```text
新增  src/main/java/.../document/cleaner/HeadingNumbering.java          (编号深度解析，含罗马歧义消解)
新增  src/main/java/.../document/cleaner/SectionHierarchyRepair.java    (§5.2 同级提级 + 审计)
改    src/main/java/.../document/domain/Section.java                    (+rawLevel 字段)
改    src/main/java/.../document/parser/marker/MarkerSectionBuilder.java (解析期写入 rawLevel)
改    src/main/java/.../document/cleaner/PaperCleaner.java              (第 9 步接入)
改    src/main/java/.../document/cleaner/CanonicalDocumentValidator.java (numberingDepth 委托 HeadingNumbering，修正假阳性)
新增  src/test/java/.../document/cleaner/SectionHierarchyRepairTest.java (9 tests)
改    4 处 PaperCleaner 构造点 (RealPaperQaTest / CleanerImprovementTest / PaperCleanerTest / CanonicalDocumentPipelineTest)
改    src/test/java/.../document/pipeline/RealPaperQaTest.java          (层级证据采集诊断 + 简化编号解析)
重生成 src/test/output/canonical/*                                       (5 篇，走新管道)
新增  src/test/output/reports/层级纠偏_rawLevel与编号同级修复.md         (本文)
```

---

## 九、如何运行

```powershell
# 全量测试
mvn test

# §5.2 同级修复单测
mvn test "-Dtest=SectionHierarchyRepairTest"

# 从 marker-raw 重新生成 canonical（走新管道）
mvn test "-Dtest=RealPaperQaTest#rawJsonDir_cleanOnly_savesOutput" "-Dmarker.qa.rawDir=src/test/output/marker-raw"

# 单篇查看全部 warning / 指标（可看到 SIBLING_REPAIRED_BY_NUMBERING 与 §9 指标）
mvn test "-Dtest=RealPaperQaTest#rawJson_cleanOnly_savesOutput" "-Dmarker.qa.raw=src/test/output/marker-raw/<file>.json"

# 层级证据采集（rawLevel vs 最终树 / 编号冲突 / 同 rawLevel 却父子）
mvn test "-Dtest=RealPaperQaTest#hierarchyDiagnostic_forReview" "-Dchunker.qa.hierarchyDiag=true"
```
