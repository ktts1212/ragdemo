# Skill: RAG Harness 评测工程设计

## 1. 适用场景

本 Skill 适用于一个已经完成基础功能的 RAG 文献问答系统，用于批量测试系统效果、定位问题、比较优化前后差异。

当前系统假设具备以下能力：

- 文档上传与入库
- 文本切分 chunk
- Embedding
- PostgreSQL + pgvector 向量检索
- topK / minScore 检索参数
- Query Rewrite
- 短期记忆、滚动摘要、结构化状态
- LLM 回答
- sources 引用返回

Harness 的目标不是替代业务功能，而是作为研发阶段的评测脚手架，用来回答：

- Query Rewrite 是否改写正确？
- 检索到的 chunk 是否相关？
- sources 是否支撑答案？
- 答案是否忠实资料？
- 是否存在资料不足时乱答？
- 是否出现参考文献区干扰正文问题？
- 优化 chunk、metadata、rerank、混合检索后效果是否真的提升？

---

## 2. 核心定义

RAG Harness 可以理解为：

> 一套专门用于批量测试 RAG 系统效果的评测工程。

它的基本流程是：

```text
固定测试问题集
↓
批量调用 RAG 系统
↓
记录 rewrittenQuestion、retrieved chunks、scores、answer、sources
↓
计算基础指标
↓
输出 JSON / CSV 报告
↓
人工复核失败样例
```

Harness 不是普通单元测试。

普通测试主要验证：

```text
接口能不能通
数据库能不能查
JSON 能不能解析
```

RAG Harness 验证的是：

```text
查得准不准
答得稳不稳
引用靠不靠谱
资料不足时是否拒答
优化前后是否有提升
```

---

## 3. 当前阶段的 Harness 定位

当前 chunk 尚未标注：

- section_type
- section_title
- is_reference_section
- abstract / methods / results / references 等结构字段

因此第一版 Harness 不应依赖章节元数据，而应采用：

```text
关键词匹配
疑似参考文献规则
人工标注
检索结果可观测性
```

第一版目标不是全自动评分，而是让系统输出足够多的中间结果，方便判断问题出在哪里。

第一版 Harness 重点提供：

- 原始问题 question
- 改写问题 rewrittenQuestion
- topK 检索 chunk
- 每个 chunk 的 score
- 每个 chunk 的 page / chunkIndex
- 每个 chunk 的 preview
- 最终回答 answer
- sources
- 自动粗评结果
- 人工评估字段 manualPass
- 失败原因 failureReason

---

## 4. Harness V1 评测方式

由于当前 chunk 还没有章节元数据，V1 使用以下字段评估：

```text
expectedKeywordsInAnswer
expectedKeywordsInChunks
shouldAnswer
shouldAvoidReferenceChunks
shouldPreferReferenceChunks
manualPass
failureReason
```

### 4.1 answerKeywordHit

判断最终答案是否包含预期关键词。

例如：

```json
"expectedKeywordsInAnswer": ["CNN", "LSTM"]
```

如果 answer 中出现 CNN 和 LSTM，则 answerKeywordHit = true。

---

### 4.2 chunkKeywordHit

判断 topK 检索到的 chunks 中是否包含预期关键词。

例如：

```json
"expectedKeywordsInChunks": ["MIT-BIH", "dataset"]
```

如果 retrieved chunks 中出现这些关键词，则 chunkKeywordHit = true。

---

### 4.3 suspectedReferenceLeak

当测试用例设置：

```json
"shouldAvoidReferenceChunks": true
```

如果 topK chunk 中疑似出现参考文献内容，则 suspectedReferenceLeak = true。

疑似参考文献规则可先用简单文本规则判断：

```text
References
doi
et al.
[1] / [2] / [12]
vol.
pp.
journal
IEEE
Elsevier
Springer
```

示例伪代码：

```java
boolean isSuspectedReference(String text) {
    String lower = text.toLowerCase();

    return lower.contains("references")
            || lower.contains("doi")
            || lower.contains(" et al")
            || lower.matches("(?s).*\\[\\d+\\].*")
            || lower.matches("(?s).*\\bvol\\.\\s*\\d+.*")
            || lower.matches("(?s).*\\bpp\\.\\s*\\d+.*")
            || lower.contains("journal")
            || lower.contains("ieee")
            || lower.contains("elsevier")
            || lower.contains("springer");
}
```

注意：这是粗略规则，不要求完全准确。后续加 section_type 后，应替换为 metadata 判断。

---

### 4.4 refusalCorrect

当测试用例设置：

```json
"shouldAnswer": false
```

系统应该回答资料不足或无法确定。

可以检测 answer 是否包含：

```text
无法确定
资料不足
未提及
没有相关信息
根据现有资料无法判断
```

如果包含，则 refusalCorrect = true。

---

### 4.5 manualPass

自动指标只能粗评，最终保留人工判断字段：

```text
manualPass = true / false / null
```

人工判断时重点看：

- 检索 chunk 是否真的相关
- 答案是否被 sources 支撑
- 是否引用了错误资料
- 是否把参考文献内容当成正文结论
- 是否资料不足却乱答

---

## 5. 测试用例文件设计

建议创建：

```text
src/test/resources/rag-eval/ecg_eval_cases.json
```

示例：

```json
[
  {
    "caseId": "ecg_overview_001",
    "question": "这篇论文主要研究什么问题？",
    "knowledgeBaseId": 1,
    "topK": 5,
    "minScore": 0.3,
    "model": "deepseek",
    "expectedKeywordsInAnswer": ["ECG"],
    "expectedKeywordsInChunks": ["ECG"],
    "shouldAnswer": true,
    "shouldAvoidReferenceChunks": true,
    "notes": "概述类问题，应优先命中摘要、引言或正文内容。"
  },
  {
    "caseId": "ecg_method_001",
    "question": "这篇论文用了什么方法进行 ECG 分类？",
    "knowledgeBaseId": 1,
    "topK": 5,
    "minScore": 0.3,
    "model": "deepseek",
    "expectedKeywordsInAnswer": ["CNN", "LSTM"],
    "expectedKeywordsInChunks": ["CNN", "LSTM", "model"],
    "shouldAnswer": true,
    "shouldAvoidReferenceChunks": true,
    "notes": "方法类问题，理想情况下不应主要依赖参考文献区。"
  },
  {
    "caseId": "ecg_dataset_001",
    "question": "这篇论文实验使用了哪个数据集？",
    "knowledgeBaseId": 1,
    "topK": 5,
    "minScore": 0.3,
    "model": "deepseek",
    "expectedKeywordsInAnswer": ["MIT-BIH"],
    "expectedKeywordsInChunks": ["MIT-BIH", "dataset"],
    "shouldAnswer": true,
    "shouldAvoidReferenceChunks": true,
    "notes": "数据集问题，应命中实验设置或数据集描述。"
  },
  {
    "caseId": "ecg_reference_001",
    "question": "这篇论文参考文献中有哪些 ECG 相关研究？",
    "knowledgeBaseId": 1,
    "topK": 8,
    "minScore": 0.3,
    "model": "deepseek",
    "expectedKeywordsInAnswer": ["ECG"],
    "expectedKeywordsInChunks": ["ECG"],
    "shouldAnswer": true,
    "shouldPreferReferenceChunks": true,
    "notes": "参考文献问题，允许命中参考文献区。"
  },
  {
    "caseId": "ecg_missing_001",
    "question": "这篇论文是否在中国人群上做了外部验证？",
    "knowledgeBaseId": 1,
    "topK": 5,
    "minScore": 0.3,
    "model": "deepseek",
    "expectedKeywordsInAnswer": [],
    "expectedKeywordsInChunks": [],
    "shouldAnswer": false,
    "expectedBehavior": "资料不足时应回答无法确定。",
    "shouldAvoidReferenceChunks": true,
    "notes": "测试资料不足时是否编造。"
  }
]
```

---

## 6. 第一批测试问题设计

建议第一批先写 30 条左右，不要一开始追求很多。

覆盖：

```text
5 条：论文概述类
5 条：方法 / 模型类
5 条：数据集 / 实验设置类
5 条：结果 / 指标类
5 条：优点 / 局限 / 讨论类
3 条：参考文献类
2 条：资料不足类
```

示例问题：

```text
1. 这篇论文主要研究什么问题？
2. 这篇论文提出了什么方法？
3. 该方法包含哪些主要模块？
4. 该方法为什么适合 ECG 分析？
5. 实验使用了哪个数据集？
6. 数据集规模是多少？
7. 使用了哪些评价指标？
8. 主要实验结果是什么？
9. 和 baseline 相比有什么提升？
10. 作者提到了哪些局限性？
11. 这篇论文参考文献中有哪些 ECG 深度学习研究？
12. 文中是否提到了外部验证？
13. 文中是否在中国人群上验证？
```

---

## 7. 推荐工程结构

推荐在项目中新增：

```text
com.example.ragdemo.eval
├── RagEvalRunner.java
├── RagEvalService.java
├── RagEvalCase.java
├── RagEvalResult.java
├── EvalRetrievedChunk.java
├── RagEvalMetricCalculator.java
├── RagEvalCaseLoader.java
└── RagEvalResultWriter.java
```

资源目录：

```text
src/test/resources/rag-eval
├── ecg_eval_cases.json
└── output
    ├── rag_eval_result.json
    └── rag_eval_result.csv
```

如果希望服务器上直接运行，可以把 eval cases 放到：

```text
src/main/resources/rag-eval
```

或者支持通过命令行传入文件路径。

---

## 8. 数据类设计

### 8.1 RagEvalCase

```java
public class RagEvalCase {

    private String caseId;

    private Long knowledgeBaseId;

    private String question;

    private Integer topK;

    private Double minScore;

    private String model;

    private List<String> expectedKeywordsInAnswer;

    private List<String> expectedKeywordsInChunks;

    private Boolean shouldAnswer;

    private Boolean shouldAvoidReferenceChunks;

    private Boolean shouldPreferReferenceChunks;

    private String expectedBehavior;

    private String notes;

    // getter / setter
}
```

---

### 8.2 RagEvalResult

```java
public class RagEvalResult {

    private String caseId;

    private String question;

    private String rewrittenQuestion;

    private String answer;

    private List<EvalRetrievedChunk> retrievedChunks;

    private Boolean answerKeywordHit;

    private Boolean chunkKeywordHit;

    private Boolean suspectedReferenceLeak;

    private Boolean refusalCorrect;

    private Boolean autoPass;

    private Boolean manualPass;

    private String failureReason;

    private String notes;

    // getter / setter
}
```

---

### 8.3 EvalRetrievedChunk

```java
public class EvalRetrievedChunk {

    private String docName;

    private Integer page;

    private Integer chunkIndex;

    private Double score;

    private String preview;

    private String content;

    private Boolean suspectedReference;

    // 未来支持 metadata 后可增加：
    private String sectionType;
    private String sectionTitle;
    private Boolean isReferenceSection;

    // getter / setter
}
```

---

## 9. ChatResponseDto 建议增加调试字段

为了让 Harness 有效，ChatResponseDto 建议至少返回：

```java
public class ChatResponseDto {

    private String conversationId;

    private String answer;

    private String rewrittenQuestion;

    private List<SourceReference> sources;

    // 调试字段，开发阶段建议保留
    private String retrievalQuestion;

    private String memorySummary;

    private String sessionState;

    // 如果 sources 已经足够详细，可以不单独返回 retrievedChunks
    private List<RetrievedChunkDto> retrievedChunks;
}
```

SourceReference 建议包含：

```java
public class SourceReference {

    private String docName;

    private Integer page;

    private Integer chunkIndex;

    private Double score;

    private String preview;

    private String content;

    // 未来 metadata 优化后增加
    private String sectionType;

    private String sectionTitle;
}
```

---

## 10. RagEvalRunner

RagEvalRunner 是评测入口。

职责：

```text
1. 判断是否启用 rag-eval
2. 加载 eval cases
3. 批量执行 case
4. 收集 result
5. 输出 JSON / CSV
6. 打印汇总指标
```

伪代码：

```java
@Component
public class RagEvalRunner implements CommandLineRunner {

    private final RagEvalCaseLoader caseLoader;
    private final RagEvalService ragEvalService;
    private final RagEvalResultWriter resultWriter;

    @Override
    public void run(String... args) throws Exception {
        boolean enabled = Arrays.asList(args).contains("--rag-eval");

        if (!enabled) {
            return;
        }

        List<RagEvalCase> cases = caseLoader.load(
                "src/test/resources/rag-eval/ecg_eval_cases.json"
        );

        List<RagEvalResult> results = new ArrayList<>();

        for (RagEvalCase evalCase : cases) {
            RagEvalResult result = ragEvalService.runCase(evalCase);
            results.add(result);
        }

        resultWriter.writeJson(results, "src/test/resources/rag-eval/output/rag_eval_result.json");
        resultWriter.writeCsv(results, "src/test/resources/rag-eval/output/rag_eval_result.csv");

        printSummary(results);
    }

    private void printSummary(List<RagEvalResult> results) {
        long total = results.size();
        long autoPass = results.stream()
                .filter(r -> Boolean.TRUE.equals(r.getAutoPass()))
                .count();

        long referenceLeak = results.stream()
                .filter(r -> Boolean.TRUE.equals(r.getSuspectedReferenceLeak()))
                .count();

        System.out.println("RAG Eval Finished");
        System.out.println("Total: " + total);
        System.out.println("Auto Pass: " + autoPass);
        System.out.println("Reference Leak: " + referenceLeak);
    }
}
```

运行方式：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--rag-eval"
```

或：

```bash
java -jar ragdemo.jar --rag-eval
```

---

## 11. RagEvalService

第一版建议直接调用 ChatService，而不是 HTTP。

优点：

- 复用现有业务逻辑
- 不需要额外启动接口
- 调试方便
- 适合工程内部评测

伪代码：

```java
@Service
public class RagEvalService {

    private final ChatService chatService;
    private final RagEvalMetricCalculator metricCalculator;

    public RagEvalResult runCase(RagEvalCase evalCase) {

        ChatRequestDto request = new ChatRequestDto();
        request.setKnowledgeBaseId(evalCase.getKnowledgeBaseId());
        request.setQuestion(evalCase.getQuestion());
        request.setTopK(evalCase.getTopK());
        request.setMinScore(evalCase.getMinScore());
        request.setModel(evalCase.getModel());

        ChatResponseDto response = chatService.chat(request);

        RagEvalResult result = buildResult(evalCase, response);

        metricCalculator.calculate(evalCase, result);

        return result;
    }

    private RagEvalResult buildResult(RagEvalCase evalCase, ChatResponseDto response) {
        RagEvalResult result = new RagEvalResult();

        result.setCaseId(evalCase.getCaseId());
        result.setQuestion(evalCase.getQuestion());
        result.setRewrittenQuestion(response.getRewrittenQuestion());
        result.setAnswer(response.getAnswer());
        result.setNotes(evalCase.getNotes());

        List<EvalRetrievedChunk> chunks = response.getSources().stream()
                .map(this::toEvalChunk)
                .toList();

        result.setRetrievedChunks(chunks);

        return result;
    }

    private EvalRetrievedChunk toEvalChunk(SourceReference source) {
        EvalRetrievedChunk chunk = new EvalRetrievedChunk();

        chunk.setDocName(source.getDocName());
        chunk.setPage(source.getPage());
        chunk.setChunkIndex(source.getChunkIndex());
        chunk.setScore(source.getScore());
        chunk.setPreview(source.getPreview());
        chunk.setContent(source.getContent());

        return chunk;
    }
}
```

后续可以增加 HTTP E2E 版本：

```text
Harness → HTTP POST /api/chat → Controller → Service → DB → LLM
```

但第一版内部调用 ChatService 即可。

---

## 12. RagEvalMetricCalculator

第一版采用规则指标，不做复杂 LLM-as-Judge。

```java
@Component
public class RagEvalMetricCalculator {

    public void calculate(RagEvalCase evalCase, RagEvalResult result) {

        result.setAnswerKeywordHit(
                checkKeywords(result.getAnswer(), evalCase.getExpectedKeywordsInAnswer())
        );

        result.setChunkKeywordHit(
                checkChunkKeywords(result.getRetrievedChunks(), evalCase.getExpectedKeywordsInChunks())
        );

        markSuspectedReferenceChunks(result.getRetrievedChunks());

        result.setSuspectedReferenceLeak(
                checkReferenceLeak(evalCase, result.getRetrievedChunks())
        );

        result.setRefusalCorrect(
                checkRefusal(evalCase, result.getAnswer())
        );

        result.setAutoPass(calculateAutoPass(evalCase, result));
    }

    private boolean checkKeywords(String text, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }

        if (text == null) {
            return false;
        }

        String lower = text.toLowerCase();

        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }

            if (!lower.contains(keyword.toLowerCase())) {
                return false;
            }
        }

        return true;
    }

    private boolean checkChunkKeywords(List<EvalRetrievedChunk> chunks, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }

        String allContent = chunks.stream()
                .map(chunk -> {
                    String preview = chunk.getPreview() == null ? "" : chunk.getPreview();
                    String content = chunk.getContent() == null ? "" : chunk.getContent();
                    return preview + "\n" + content;
                })
                .collect(Collectors.joining("\n"))
                .toLowerCase();

        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }

            if (!allContent.contains(keyword.toLowerCase())) {
                return false;
            }
        }

        return true;
    }

    private void markSuspectedReferenceChunks(List<EvalRetrievedChunk> chunks) {
        for (EvalRetrievedChunk chunk : chunks) {
            String text = (chunk.getPreview() == null ? "" : chunk.getPreview())
                    + "\n"
                    + (chunk.getContent() == null ? "" : chunk.getContent());

            chunk.setSuspectedReference(isSuspectedReference(text));
        }
    }

    private boolean isSuspectedReference(String text) {
        if (text == null) {
            return false;
        }

        String lower = text.toLowerCase();

        return lower.contains("references")
                || lower.contains("doi")
                || lower.contains(" et al")
                || lower.matches("(?s).*\\[\\d+\\].*")
                || lower.matches("(?s).*\\bvol\\.\\s*\\d+.*")
                || lower.matches("(?s).*\\bpp\\.\\s*\\d+.*")
                || lower.contains("journal")
                || lower.contains("ieee")
                || lower.contains("elsevier")
                || lower.contains("springer");
    }

    private boolean checkReferenceLeak(RagEvalCase evalCase, List<EvalRetrievedChunk> chunks) {
        if (!Boolean.TRUE.equals(evalCase.getShouldAvoidReferenceChunks())) {
            return false;
        }

        return chunks.stream()
                .anyMatch(chunk -> Boolean.TRUE.equals(chunk.getSuspectedReference()));
    }

    private boolean checkRefusal(RagEvalCase evalCase, String answer) {
        if (!Boolean.FALSE.equals(evalCase.getShouldAnswer())) {
            return true;
        }

        if (answer == null) {
            return false;
        }

        return answer.contains("无法确定")
                || answer.contains("资料不足")
                || answer.contains("未提及")
                || answer.contains("没有相关信息")
                || answer.contains("无法判断");
    }

    private boolean calculateAutoPass(RagEvalCase evalCase, RagEvalResult result) {
        if (Boolean.TRUE.equals(evalCase.getShouldAnswer())) {
            if (Boolean.FALSE.equals(result.getAnswerKeywordHit())) {
                return false;
            }

            if (Boolean.FALSE.equals(result.getChunkKeywordHit())) {
                return false;
            }
        }

        if (Boolean.TRUE.equals(result.getSuspectedReferenceLeak())) {
            return false;
        }

        if (Boolean.FALSE.equals(evalCase.getShouldAnswer())
                && Boolean.FALSE.equals(result.getRefusalCorrect())) {
            return false;
        }

        return true;
    }
}
```

---

## 13. FailureReason 设计

建议人工复核时使用固定失败原因：

```text
PASS
REWRITE_BAD
RETRIEVAL_MISS
REFERENCE_LEAK
ANSWER_UNGROUNDED
HALLUCINATION
CITATION_WRONG
QUESTION_AMBIGUOUS
CHUNK_TOO_SMALL
CHUNK_TOO_LARGE
PDF_PARSE_ERROR
UNKNOWN
```

含义：

- PASS：通过
- REWRITE_BAD：问题改写错误
- RETRIEVAL_MISS：没有检索到相关 chunk
- REFERENCE_LEAK：正文问题混入参考文献区
- ANSWER_UNGROUNDED：答案没有被 sources 支撑
- HALLUCINATION：资料不足却编造
- CITATION_WRONG：引用编号或来源不准确
- QUESTION_AMBIGUOUS：测试问题本身歧义太大
- CHUNK_TOO_SMALL：chunk 太碎，信息不完整
- CHUNK_TOO_LARGE：chunk 太大，噪声太多
- PDF_PARSE_ERROR：PDF 解析质量差
- UNKNOWN：暂未分类

---

## 14. 输出报告设计

### 14.1 JSON 报告

保留完整结果，方便后续分析。

```json
{
  "caseId": "ecg_method_001",
  "question": "这篇论文用了什么方法进行 ECG 分类？",
  "rewrittenQuestion": "这篇论文中用于 ECG 分类的方法是什么？",
  "answer": "根据资料1，本文使用 CNN-LSTM 方法进行 ECG 分类。",
  "retrievedChunks": [
    {
      "docName": "ecg_paper.pdf",
      "page": 4,
      "chunkIndex": 12,
      "score": 0.81,
      "preview": "The proposed CNN-LSTM model...",
      "suspectedReference": false
    },
    {
      "docName": "ecg_paper.pdf",
      "page": 11,
      "chunkIndex": 45,
      "score": 0.69,
      "preview": "[12] Smith et al. Deep learning for ECG classification...",
      "suspectedReference": true
    }
  ],
  "answerKeywordHit": true,
  "chunkKeywordHit": true,
  "suspectedReferenceLeak": true,
  "refusalCorrect": true,
  "autoPass": false,
  "manualPass": null,
  "failureReason": "REFERENCE_LEAK",
  "notes": "答案正确，但 topK 混入疑似参考文献 chunk。"
}
```

---

### 14.2 CSV 报告

方便用 Excel 查看。

字段建议：

```text
caseId
question
rewrittenQuestion
answerKeywordHit
chunkKeywordHit
suspectedReferenceLeak
refusalCorrect
autoPass
manualPass
failureReason
answer
topSources
notes
```

`topSources` 可以压缩成：

```text
chunkIndex=12,page=4,score=0.81,ref=false | chunkIndex=45,page=11,score=0.69,ref=true
```

---

## 15. Harness V1 使用方式

运行：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--rag-eval"
```

或：

```bash
java -jar ragdemo.jar --rag-eval
```

输出：

```text
src/test/resources/rag-eval/output/rag_eval_result.json
src/test/resources/rag-eval/output/rag_eval_result.csv
```

查看 CSV 后，人工补充：

```text
manualPass
failureReason
notes
```

然后统计失败类型。

---

## 16. 后续 Harness V2 升级方向

当 chunk 优化后，增加 metadata：

```text
section_type
section_title
is_reference_section
page_number
```

eval case 可以升级为：

```json
{
  "caseId": "ecg_method_001",
  "question": "这篇论文用了什么方法进行 ECG 分类？",
  "expectedSectionTypes": ["abstract", "methods", "model"],
  "forbiddenSectionTypes": ["references"],
  "expectedKeywordsInAnswer": ["CNN", "LSTM"],
  "shouldAnswer": true
}
```

新增指标：

```text
sectionHit
forbiddenSectionHit
referenceLeakRate
citationSectionCorrect
```

此时 suspectedReferenceLeak 不再依赖文本规则，而是直接用：

```java
chunk.getSectionType().equals("references")
```

或：

```java
Boolean.TRUE.equals(chunk.getIsReferenceSection())
```

---

## 17. 优化对比实验

Harness 最重要的用途是比较优化前后效果。

例如三种方案：

```text
A. baseline：当前固定长度 chunk
B. section-metadata：chunk 增加章节元数据
C. reference-split：参考文献单条拆分 + section filter
```

分别运行：

```bash
java -jar ragdemo.jar --rag-eval --eval-profile=baseline
java -jar ragdemo.jar --rag-eval --eval-profile=section-metadata
java -jar ragdemo.jar --rag-eval --eval-profile=reference-split
```

比较：

```text
autoPass 是否提升
suspectedReferenceLeak 是否下降
retrieval miss 是否减少
hallucination 是否减少
citation wrong 是否减少
```

核心原则：

> 不凭感觉优化，用同一批 eval cases 对比优化前后的结果。

---

## 18. 暂不建议第一版使用 LLM-as-Judge

LLM-as-Judge 是让另一个模型判断答案是否正确。

第一版不建议做，原因：

```text
1. 多一次模型调用，成本更高
2. 评判本身不一定稳定
3. 当前阶段更需要看清检索链路，而不是自动打分
4. 人工复核 30 条 case 更可靠
```

第一版建议：

```text
规则指标 + 人工复核
```

等评测集超过 100 条、系统稳定后，再考虑 LLM-as-Judge。

---

## 19. 当前落地顺序

推荐按以下顺序实现：

```text
第一步：定义 rag_eval_cases.json
先写 15~30 条，不要一开始太多。

第二步：让 ChatResponseDto 暴露调试字段
至少包括 rewrittenQuestion、sources、score、page、chunkIndex、preview。

第三步：实现 RagEvalCase / RagEvalResult / EvalRetrievedChunk。

第四步：实现 RagEvalCaseLoader
从 JSON 文件读取测试用例。

第五步：实现 RagEvalService
批量调用 ChatService。

第六步：实现 RagEvalMetricCalculator
计算 answerKeywordHit、chunkKeywordHit、suspectedReferenceLeak、refusalCorrect、autoPass。

第七步：实现 RagEvalResultWriter
输出 JSON 和 CSV。

第八步：人工查看 CSV
补充 manualPass、failureReason、notes。

第九步：根据失败类型决定优化方向
例如 Query Rewrite、chunk、metadata、reference split、rerank。
```

---

## 20. 关键结论

Harness V1 的核心目标不是全自动评分，而是提供 RAG 链路可观测性。

当前最小可用版本应做到：

```text
能批量跑固定问题
能记录 rewrittenQuestion
能记录 topK chunks
能记录 score 和 preview
能记录 answer 和 sources
能粗略检测关键词命中
能粗略检测疑似参考文献泄漏
能检测资料不足时是否拒答
能输出 CSV / JSON
能人工标注失败原因
```

一句话总结：

> RAG Harness 就是用固定测试问题批量检查“问题 → 改写 → 检索 → 回答 → 引用”的全过程。当前 chunk 尚无章节元数据时，先用关键词、疑似参考文献规则和人工标注做 V1；后续加 section_type、is_reference_section 后，再升级为更自动、更精确的 V2。
