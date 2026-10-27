package com.example.ragdemo.memory.l3;

import org.springframework.stereotype.Component;

/**
 * L3 唯一可信身份来源（Memory V1 · Phase 2）。
 * <p>规则（与 Phase 0/1 结论一致）：
 * <ul>
 *   <li>身份**只**来自服务端配置 {@code rag.memory.l3.single-user-principal}；HTTP 请求体、Tool 输出、
 *       模型输出都不得设置 principalId（本类不提供任何 setter/入参）；</li>
 *   <li>L3 关闭 or 身份为空 ⇒ {@link #available()} 为 false，调用方必须**跳过**写入（而不是退化成全局 owner）；</li>
 *   <li>本类不宣称多租户隔离：V1 是"受限单用户开发模式"。</li>
 * </ul>
 */
@Component
public class L3PrincipalResolver {

    private final MemoryProperties properties;

    public L3PrincipalResolver(MemoryProperties properties) {
        this.properties = properties;
    }

    /** L3 是否可写入（开关 + 可信身份都具备）。 */
    public boolean available() {
        return properties.getL3().isEnabled() && !principal().isBlank();
    }

    /**
     * 服务端身份；未配置时返回空串（**不返回 null、不用默认用户名兜底**）。
     * <p>调用方必须先用 {@link #available()} 判断。
     */
    public String principal() {
        String value = properties.getL3().getSingleUserPrincipal();
        return value == null ? "" : value.trim();
    }

    /** 写入前的一致性断言：L3 未就绪时抛出明确异常，避免"静默按空身份写入"。 */
    public String requirePrincipal() {
        if (!properties.getL3().isEnabled()) {
            throw new IllegalStateException("L3 未启用（rag.memory.l3.enabled=false）");
        }
        String value = principal();
        if (value.isBlank()) {
            throw new IllegalStateException("缺少服务端可信身份（rag.memory.l3.single-user-principal）");
        }
        return value;
    }
}
