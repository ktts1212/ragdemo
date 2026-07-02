# LangChain4j 多模型动态切换设计 Skill

## 1. 适用场景

当 Spring Boot + LangChain4j 项目中需要同时支持多个大模型，例如：

- DeepSeek
- 智谱 GLM
- OpenAI
- Ollama 本地模型
- Qwen / Moonshot / 其他 OpenAI-compatible 模型

并且希望用户可以在接口参数、配置文件或业务逻辑中动态选择模型时，应采用“配置化 + 模型工厂”的设计方式。

---

## 2. 核心原则

LangChain4j 本身提供统一的模型抽象接口，例如：

```java
ChatModel
EmbeddingModel
StreamingChatModel
```

业务代码不应直接依赖具体模型实现类，例如：

```java
OpenAiChatModel
OllamaChatModel
GoogleAiGeminiChatModel
AnthropicChatModel
```

而应依赖统一接口：

```java
ChatModel chatModel = chatModelFactory.getModel(modelKey);
```

这样可以降低业务代码和具体模型厂商之间的耦合。

---

## 3. 为什么不直接使用 Spring Boot Starter 自动装配

LangChain4j 的 Spring Boot Starter 适合单模型场景，例如只配置一个 OpenAI 模型，然后由 Spring 自动创建一个 `ChatModel` Bean。

但是在多模型动态切换场景下，例如：

```text
/api/chat?model=deepseek
/api/chat?model=zhipu
/api/chat?model=ollama
```

业务需要根据请求参数动态选择不同模型。

这种情况下，不建议依赖单个自动装配的 `ChatModel` Bean，而应自行封装：

```text
application.yml
    ↓
RagProperties
    ↓
ChatModelFactory
    ↓
ChatService / RagService
```

---

## 4. 推荐架构

```text
application.yml
   ↓
@ConfigurationProperties 读取模型配置
   ↓
ChatModelFactory 根据 modelKey 创建并缓存 ChatModel
   ↓
ChatService / RagService 根据请求参数选择模型
   ↓
Controller 暴露接口
```

业务代码只关心：

```java
ChatModel chatModel = chatModelFactory.getModel(modelKey);
String answer = chatModel.chat(question);
```

不直接关心底层是 DeepSeek、智谱、OpenAI 还是 Ollama。

---

## 5. application.yml 推荐写法

```yaml
rag:
  default-model: deepseek

  models:
    deepseek:
      provider: openai-compatible
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY}
      model-name: deepseek-chat
      temperature: 0.2
      max-output-tokens: 2000

    zhipu:
      provider: openai-compatible
      base-url: https://open.bigmodel.cn/api/paas/v4
      api-key: ${ZHIPU_API_KEY}
      model-name: glm-4-flash
      temperature: 0.2
      max-output-tokens: 2000

    ollama-chat:
      provider: ollama
      base-url: http://localhost:11434
      api-key: ollama
      model-name: qwen2.5:7b
      temperature: 0.2
      max-output-tokens: 2000
```

注意：

- API Key 不应明文写死，应使用环境变量。
- OpenAI-compatible 模型统一使用 `provider: openai-compatible`。
- Ollama 本地模型可以使用 `provider: ollama`。
- 建议使用 `model-name`，与 LangChain4j 中的 `.modelName()` 保持一致。

---

## 6. 配置读取类设计

使用 `@ConfigurationProperties(prefix = "rag")` 读取配置。

核心字段包括：

```java
private String defaultModel;

private Map<String, ModelConfig> models;
```

其中 `ModelConfig` 包含：

```java
private String provider;
private String baseUrl;
private String apiKey;
private String modelName;
private Double temperature;
private Integer maxOutputTokens;
```

Spring Boot 会自动完成中划线到驼峰的映射：

```text
default-model       -> defaultModel
model-name          -> modelName
max-output-tokens   -> maxOutputTokens
base-url            -> baseUrl
api-key             -> apiKey
```

---

## 7. ChatModelFactory 设计

`ChatModelFactory` 是多模型切换的核心。

职责：

1. 根据 `modelKey` 找到对应配置。
2. 根据 `provider` 判断使用哪种 LangChain4j 模型实现。
3. 创建 `ChatModel`。
4. 缓存已创建的模型，避免每次请求重复创建。

核心伪代码：

```java
@Component
public class ChatModelFactory {

    private final RagProperties ragProperties;

    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModel getModel(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            modelKey = ragProperties.getDefaultModel();
        }

        String finalModelKey = modelKey;

        return cache.computeIfAbsent(finalModelKey, this::createModel);
    }

    private ChatModel createModel(String modelKey) {
        ModelConfig config = ragProperties.getModels().get(modelKey);

        if (config == null) {
            throw new IllegalArgumentException("未找到模型配置: " + modelKey);
        }

        return switch (config.getProvider()) {
            case "openai-compatible" -> createOpenAiCompatibleModel(config);
            case "ollama" -> createOllamaModel(config);
            default -> throw new IllegalArgumentException("不支持的 provider: " + config.getProvider());
        };
    }

    private ChatModel createOpenAiCompatibleModel(ModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxOutputTokens())
                .build();
    }

    private ChatModel createOllamaModel(ModelConfig config) {
        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .build();
    }
}
```

---

## 8. OpenAI-compatible 模型处理原则

如果模型服务兼容 OpenAI API 格式，例如支持：

```text
/v1/chat/completions
```

并使用类似 OpenAI 的请求结构：

```json
{
  "model": "xxx",
  "messages": [
    {
      "role": "user",
      "content": "你好"
    }
  ],
  "temperature": 0.2
}
```

则可以使用：

```java
OpenAiChatModel
```

并通过不同配置切换：

```java
.baseUrl(...)
.apiKey(...)
.modelName(...)
```

例如：

```text
DeepSeek      -> OpenAiChatModel + DeepSeek baseUrl
智谱 GLM      -> OpenAiChatModel + 智谱 baseUrl
Qwen          -> OpenAiChatModel + DashScope compatible baseUrl
Moonshot      -> OpenAiChatModel + Moonshot baseUrl
```

但如果 LangChain4j 已经提供专门实现类，例如：

```java
OllamaChatModel
```

则优先使用专门实现类，语义更清晰。

---

## 9. 业务层使用方式

业务层不直接创建模型。

错误写法：

```java
OpenAiChatModel.builder()
        .apiKey(...)
        .modelName(...)
        .build();
```

推荐写法：

```java
ChatModel chatModel = chatModelFactory.getModel(modelKey);
String answer = chatModel.chat(question);
```

普通问答示例：

```java
@Service
public class ChatService {

    private final ChatModelFactory chatModelFactory;

    public String chat(String modelKey, String question) {
        ChatModel chatModel = chatModelFactory.getModel(modelKey);
        return chatModel.chat(question);
    }
}
```

---

## 10. RAG 场景中的使用方式

RAG 流程中，检索逻辑和模型生成逻辑应解耦。

```text
用户问题
  ↓
Embedding
  ↓
向量检索
  ↓
拼接 Prompt
  ↓
ChatModelFactory 根据 modelKey 选择模型
  ↓
生成答案
```

示例：

```java
public String ragChat(String modelKey, String question) {

    List<String> chunks = retrievalService.retrieve(question);

    String context = String.join("\n\n", chunks);

    String prompt = """
            请基于下面资料回答问题。
            如果资料中没有答案，请回答：资料中没有相关信息。

            【资料】
            %s

            【问题】
            %s
            """.formatted(context, question);

    ChatModel chatModel = chatModelFactory.getModel(modelKey);

    return chatModel.chat(prompt);
}
```

这样可以做到：

```text
同一套 RAG 检索逻辑
不同模型负责最终回答
```

---

## 11. Controller 示例

```java
@PostMapping("/chat")
public ChatResponseDto chat(@RequestBody ChatRequestDto request) {
    String answer = chatService.chat(
            request.getModel(),
            request.getQuestion()
    );

    return new ChatResponseDto(request.getModel(), answer);
}
```

请求示例：

```json
{
  "model": "deepseek",
  "question": "什么是 RAG？"
}
```

切换智谱：

```json
{
  "model": "zhipu",
  "question": "什么是 RAG？"
}
```

不传模型时，使用 `rag.default-model`。

---

## 12. 设计优点

该设计有以下优点：

1. 模型配置集中在 `application.yml`，方便维护。
2. API Key 不写死在 Java 代码中，安全性更高。
3. 业务代码只依赖 `ChatModel` 接口，耦合度低。
4. 新增模型时，优先只改配置，不改业务逻辑。
5. RAG、普通问答、查询改写等模块都可以复用同一个模型工厂。
6. 支持不同 provider，例如 OpenAI-compatible、Ollama、Gemini、Claude 等。

---

## 13. 注意事项

1. 不要把真实 API Key 写进代码、配置文件或提交到 Git。
2. `model-name` 要和实际平台支持的模型名一致。
3. `base-url` 是否需要 `/v1` 要根据具体平台和 LangChain4j 版本测试。
4. Tool Calling、JSON 输出、图片输入等高级能力需要额外判断模型是否支持。
5. RAG 和代码解释场景建议 `temperature` 设置较低，例如 `0.1 ~ 0.3`。
6. 文案创作、头脑风暴可以适当提高 `temperature`，例如 `0.7 ~ 1.0`。
7. 模型对象建议缓存，不要每次请求都重新创建。

---

## 14. 最终记忆句

LangChain4j 提供统一模型接口，Spring Boot Starter 适合单模型自动装配；但在 DeepSeek、智谱、Ollama 等多模型动态切换场景下，应将模型配置放入 `application.yml`，使用 `@ConfigurationProperties` 读取配置，并通过 `ChatModelFactory` 根据 `modelKey` 创建和缓存不同的 `ChatModel`，业务层统一依赖 `ChatModel` 接口。
