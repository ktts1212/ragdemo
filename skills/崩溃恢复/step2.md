# Day 1 / Step 2：LangGraph4j 最小持久化工作流实现

## 任务目标

在现有 Java RAG 项目中，实现独立的 LangGraph4j 最小工作流，并通过 PostgreSQL 验证 Checkpoint 持久化。

技术环境：
- Java 17
- Spring Boot 3.3.5
- LangGraph4j 1.9.4
- PostgreSQL
- Maven

LangGraph4j BOM、Core 和 PostgreSQL Saver 已经引入，不需要重复添加依赖。

本步骤只验证基础能力，不接入真实业务。

## 一、代码实现

在 `com.example.ragdemo.workflow` 下新增独立 PoC 模块，建议包含：

- `poc/RecoveryTestState.java`
- `poc/RecoveryTestGraph.java`
- `poc/RecoveryTestRunner.java`
- `config/RecoveryTestGraphConfig.java`

可以根据实际框架 API 调整文件划分，但必须保持与现有业务代码隔离。

### 1. State 设计

至少包含：

- taskId
- currentStep
- processedCount
- executionLog

状态必须能够序列化及反序列化。

注意正确配置 LangGraph4j 的 State Channel 和 Reducer，不能假设集合更新一定是追加语义。

### 2. Graph 设计

创建：

START → PrepareNode → ProcessNode → FinishNode → END

每个节点只做简单的状态更新，不调用 Marker、Kafka、Embedding 或现有数据库业务操作。

节点返回状态增量，不直接修改共享状态对象。

### 3. PostgreSQL Checkpoint

使用 `PostgresSaverV2`，复用 Spring Boot DataSource。

要求：

- 使用 LangGraph4j 1.9.4 的真实 API。
- 优先检查官方示例及本地 JAR 源码。
- 对 StateSerializer 进行实际兼容性测试。
- 禁止使用 `dropTablesFirst(true)`。
- 不清空或修改原有 RAG 业务表。
- Checkpoint 建表只能影响 LangGraph4j 自身的表。
- 明确验证 `releaseThread` 配置对工作流状态生命周期和恢复的影响。

### 4. Runner

提供两个基本方法：

- `start(taskId)`：使用新 threadId 启动工作流。
- `getState(taskId)`：重新读取已保存的状态。

threadId 使用独立命名空间，例如 `poc-recovery-{taskId}`。

暂不实现自动恢复调度器。

## 二、测试要求

必须编写自动化测试，至少验证：

1. Graph 正常执行到 END。
2. 各节点状态正确传递。
3. PostgreSQL 中存在该 thread 的 Checkpoint。
4. 使用新的 Graph/Saver 实例，能够读取之前保存的状态。
5. 相同 threadId 与不同 threadId 不会串扰。

注意：正常执行到 END 后能够读取历史状态，不等价于证明未完成工作流能够 Resume。本步骤只验证持久化基础，真正的崩溃恢复留到 Step 4。

如果数据库连接不可用，必须明确报告失败，不得用 MemorySaver 的成功结果冒充 PostgreSQL 持久化成功。

测试不能污染现有文献数据。

## 三、严格限制

- 不修改 `AcademicIngestionService`。
- 不修改 `IngestionServiceImpl`。
- 不修改 `KafkaConsumerService`。
- 不修改任何文献解析、清洗、分块业务逻辑。
- 不引入 Agent。
- 不修改现有业务数据库表结构。
- 不实现 Embedding 批次循环。
- 不扩大到 Step 3、4、5。

## 四、交付要求

完成后输出：

1. 新增和修改的文件清单。
2. StateGraph 结构及节点功能。
3. StateSerializer 的具体选择及验证依据。
4. PostgresSaverV2 初始化方式。
5. Maven 编译和 JUnit 测试结果。
6. PostgreSQL 中 Checkpoint 的验证结果。
7. 遇到的问题与解决方法。
8. Step 3 的建议。

所有成功结论必须有实际测试、日志或数据库查询作为证据，不能只根据代码推断。

本次任务只完成 Day 1 / Step 2。