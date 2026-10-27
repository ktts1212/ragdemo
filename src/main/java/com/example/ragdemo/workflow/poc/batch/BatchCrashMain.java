package com.example.ragdemo.workflow.poc.batch;

import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.state.StateSnapshot;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * <b>真实 JVM 崩溃实验</b>的独立子进程入口（不启动 Spring，只用 JDBC + LangGraph4j）。
 * <p>
 * 由 {@code BatchRecoveryGraphTest} 通过 {@code ProcessBuilder} 以<b>新 JVM</b> 拉起；
 * 崩溃用 {@link Runtime#halt(int)}（不是抛异常：halt 立即终止 JVM、不执行 shutdown hook、
 * 不 flush 缓冲，等价于 SIGKILL），因此"结果已落库但 Checkpoint 未提交"这种窗口能被真实复现。
 * <p>
 * 用法：
 * <pre>
 *   run          &lt;taskId&gt; &lt;totalItems&gt; &lt;none|before|after&gt; &lt;crashBatchIndex&gt;
 *   resume       &lt;taskId&gt;                 # 用 GraphInput.resume()
 *   resume-noargs&lt;taskId&gt;                 # 用 GraphInput.noArgs()（行为对照）
 *   inspect      &lt;taskId&gt;                 # 只读打印当前 Checkpoint 快照，不执行图
 * </pre>
 * 数据库连接从环境变量读取（父进程通过 env 传入，避免把凭据写进代码/日志）：
 * {@code POC_DB_URL} / {@code POC_DB_USER} / {@code POC_DB_PASSWORD}。
 * <p>
 * 输出行均为稳定前缀，便于父测试断言：
 * {@code [JVM]} / {@code [EXEC]} / {@code [CRASH]} / {@code [INSPECT]} / {@code [RESUME]} / {@code [STORE]} / {@code [RESULT]}。
 */
public final class BatchCrashMain {

    private BatchCrashMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("[FATAL] usage: <mode> <taskId> [args...]");
            System.exit(2);
        }
        String mode = args[0];
        String taskId = args[1];
        System.out.println("[JVM] pid=" + ProcessHandle.current().pid() + " mode=" + mode + " taskId=" + taskId);

        DataSource dataSource = dataSourceFromEnv();
        BatchResultStore store = new BatchResultStore(dataSource);
        store.ensureSchema();

        switch (mode) {
            case "run" -> run(dataSource, store, taskId, args);
            case "resume" -> resume(dataSource, store, taskId, GraphInput.resume(), "resume()");
            case "resume-noargs" -> resume(dataSource, store, taskId, GraphInput.noArgs(), "noArgs()");
            case "inspect" -> inspect(graph(dataSource, store, BatchRecoveryGraph.CrashPolicy.none()), taskId, store);
            default -> {
                System.out.println("[FATAL] unknown mode: " + mode);
                System.exit(2);
            }
        }
    }

    /** 正常/带崩溃点地启动一次全新 thread。 */
    private static void run(DataSource dataSource, BatchResultStore store, String taskId, String[] args) throws Exception {
        int totalItems = Integer.parseInt(args[2]);
        BatchRecoveryGraph.CrashPolicy policy = switch (args[3]) {
            case "before" -> BatchRecoveryGraph.CrashPolicy.beforeBatch(Integer.parseInt(args[4]));
            case "after" -> BatchRecoveryGraph.CrashPolicy.afterResultPersist(Integer.parseInt(args[4]));
            default -> BatchRecoveryGraph.CrashPolicy.none();
        };
        System.out.println("[RUN] totalItems=" + totalItems + " crashPolicy=" + policy.mode()
                + "@" + policy.batchIndex());

        CompiledGraph<BatchRecoveryState> graph = graph(dataSource, store, policy);
        var result = graph.invoke(GraphInput.args(Map.of(
                        BatchRecoveryState.TASK_ID, taskId,
                        BatchRecoveryState.TOTAL_ITEMS, totalItems)),
                RunnableConfig.builder().threadId(BatchRecoveryRunner.threadIdOf(taskId)).build());

        System.out.println("[RESULT] status=" + result.map(BatchRecoveryState::status).orElse("<empty>")
                + " nextBatchIndex=" + result.map(BatchRecoveryState::nextBatchIndex).orElse(-1)
                + " completed=" + result.map(s -> String.valueOf(s.completedBatches())).orElse("<empty>"));
        printStore(store, taskId);
    }

    /** 在同一 threadId 上恢复执行。 */
    private static void resume(DataSource dataSource, BatchResultStore store, String taskId, GraphInput input,
            String label) {
        try {
            CompiledGraph<BatchRecoveryState> graph = graph(dataSource, store, BatchRecoveryGraph.CrashPolicy.none());
            inspect(graph, taskId, store);   // 先打印恢复前的快照：待执行节点是什么
            var result = graph.invoke(input,
                    RunnableConfig.builder().threadId(BatchRecoveryRunner.threadIdOf(taskId)).build());
            System.out.println("[RESUME] graphInput=" + label + " outcome=OK status="
                    + result.map(BatchRecoveryState::status).orElse("<empty>")
                    + " nextBatchIndex=" + result.map(BatchRecoveryState::nextBatchIndex).orElse(-1)
                    + " completed=" + result.map(s -> String.valueOf(s.completedBatches())).orElse("<empty>"));
            printStore(store, taskId);
        } catch (Throwable t) {
            System.out.println("[RESUME] graphInput=" + label + " outcome=ERROR " + t.getClass().getName()
                    + ": " + t.getMessage());
            System.out.flush();
            System.exit(3);
        }
    }

    /** 只读打印 checkpoint 快照（不执行任何节点）。 */
    private static void inspect(CompiledGraph<BatchRecoveryState> graph, String taskId, BatchResultStore store) {
        RunnableConfig config = RunnableConfig.builder().threadId(BatchRecoveryRunner.threadIdOf(taskId)).build();
        StateSnapshot<BatchRecoveryState> snapshot = graph.getState(config);
        if (snapshot == null || snapshot.state() == null) {
            System.out.println("[INSPECT] thread=" + BatchRecoveryRunner.threadIdOf(taskId) + " <no checkpoint>");
            return;
        }
        BatchRecoveryState s = snapshot.state();
        System.out.println("[INSPECT] thread=" + BatchRecoveryRunner.threadIdOf(taskId)
                + " lastNode=" + snapshot.node()
                + " nextNode=" + snapshot.next()
                + " nextBatchIndex=" + s.nextBatchIndex()
                + " totalBatches=" + s.totalBatches()
                + " status=" + s.status()
                + " completed=" + s.completedBatches()
                + " log=" + s.executionLog());
    }

    private static void printStore(BatchResultStore store, String taskId) throws Exception {
        List<Integer> batches = store.batchIndexes(taskId);
        System.out.println("[STORE] taskId=" + taskId + " rows=" + store.countRows(taskId)
                + " batches=" + batches + " details=" + store.batchDetails(taskId));
    }

    private static CompiledGraph<BatchRecoveryState> graph(DataSource dataSource, BatchResultStore store,
            BatchRecoveryGraph.CrashPolicy policy) throws Exception {
        PostgresSaverV2 saver = PostgresSaverV2.builder()
                .datasource(dataSource)
                .createTables(false)
                .stateSerializer(new BatchRecoveryStateSerializer())
                .build();
        return new BatchRecoveryGraph(store, policy).build()
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .releaseThread(false)
                        .recursionLimit(100)
                        .build());
    }

    private static DataSource dataSourceFromEnv() {
        try {
            Class.forName("org.postgresql.Driver");   // JDBC4 会自动发现；显式加载消除子进程差异
        } catch (ClassNotFoundException e) {
            System.out.println("[FATAL] postgresql driver not on child classpath");
            System.exit(2);
        }
        return new DriverManagerDataSource(requireEnv("POC_DB_URL"), requireEnv("POC_DB_USER"),
                System.getenv("POC_DB_PASSWORD"));
    }

    /** 极简 DataSource：子进程只连一个库，无需连接池，避免为此引入额外依赖。 */
    private static final class DriverManagerDataSource implements DataSource {

        private final String url;
        private final String user;
        private final String password;

        DriverManagerDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pwd) throws SQLException {
            return DriverManager.getConnection(url, username, pwd);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // 子进程实验无需日志写入器
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // 使用驱动默认
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper for " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.out.println("[FATAL] missing env: " + name);
            System.exit(2);
        }
        return value;
    }
}
