package com.example.ragdemo.memory.l3;

/**
 * Worker 故障注入点（Phase 4B）。
 *
 * <p><b>生产默认不装配</b>（{@link MemoryJobProcessor} 通过 {@code @Autowired(required=false)} 使用）：
 * 只有崩溃演练/原子性测试的子进程会提供一个实现（{@code Runtime.halt(137)} 或抛异常）。
 *
 * <p>注入点都落在**确定性边界**（不含 sleep/时间猜测）：
 * <ol>
 *   <li>{@link #AFTER_CLAIM}：已领取（RUNNING + 租约）之后、读取来源消息之前；</li>
 *   <li>{@link #AFTER_EXTRACT}：LLM 提取成功之后、策略/向量之前；</li>
 *   <li>{@link #AFTER_EMBED}：向量全部算好之后、事务之前；</li>
 *   <li>{@link #TX_BEFORE_INSERT}：短事务内、第一条记忆写入之前；</li>
 *   <li>{@link #TX_AFTER_INSERT}：短事务内、全部记忆写入之后、{@code markDone} 之前；</li>
 *   <li>{@link #AFTER_COMMIT}：事务提交成功之后（记忆 + DONE 均已落库）。</li>
 * </ol>
 */
@FunctionalInterface
public interface MemoryJobFaultHook {

    String AFTER_CLAIM = "AFTER_CLAIM";
    String AFTER_EXTRACT = "AFTER_EXTRACT";
    String AFTER_EMBED = "AFTER_EMBED";
    String TX_BEFORE_INSERT = "TX_BEFORE_INSERT";
    String TX_AFTER_INSERT = "TX_AFTER_INSERT";
    String AFTER_COMMIT = "AFTER_COMMIT";

    void at(String point);
}
