package com.example.ragdemo.agent.tools;

import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.ragdemo.agent.tools.model.ToolErrorCode;
import com.example.ragdemo.agent.tools.model.ToolResult;

/**
 * 六个 Tool 的公共基类：承载受信上下文 + 统一异常收敛。
 * <p>
 * <b>职责边界（Step 2 §6）</b>：Tool 只做「参数校验 → 传递受信上下文 → 映射稳定 DTO」，
 * 业务查询/资源管理在 Service，SQL 在 Mapper。因此本基类只有三件事：
 * <ol>
 *   <li>持有不可变的 {@link PaperToolExecutionContext}（知识库范围只在构造期绑定）；</li>
 *   <li>{@link #guarded} 把 {@link PaperToolException} 映射成稳定 {@code ToolResult} 失败结果；</li>
 *   <li>日志只打工具名/错误码/范围，<b>不打内容与内部地址</b>。</li>
 * </ol>
 */
public abstract class AbstractPaperTool {

    private static final Logger log = LoggerFactory.getLogger(AbstractPaperTool.class);

    private final PaperToolExecutionContext context;

    protected AbstractPaperTool(PaperToolExecutionContext context) {
        this.context = Objects.requireNonNull(context, "Tool 必须在受信上下文下创建");
    }

    protected final PaperToolExecutionContext context() {
        return context;
    }

    protected final long knowledgeBaseId() {
        return context.knowledgeBaseId();
    }

    protected final PaperToolLimits limits() {
        return context.limits();
    }

    /**
     * 统一错误收敛：Tool 方法体一律包在本方法内调用。
     * <p>
     * 任何未预期异常都收敛为 {@link ToolErrorCode#INTERNAL_ERROR}（不向模型暴露堆栈/路径）。
     */
    protected final <T> ToolResult<T> guarded(String toolName, Supplier<ToolResult<T>> body) {
        try {
            return body.get();
        } catch (PaperToolException e) {
            log.warn("[tool:{}] 拒绝调用: scope={}, code={}, reason={}",
                    toolName, context.describeScope(), e.code(), e.getMessage());
            return ToolResult.fail(e.toError());
        } catch (RuntimeException e) {
            log.error("[tool:{}] 内部错误: scope={}, type={}",
                    toolName, context.describeScope(), e.getClass().getSimpleName(), e);
            return ToolResult.fail(ToolErrorCode.INTERNAL_ERROR, "工具内部错误，已记录日志");
        }
    }
}
