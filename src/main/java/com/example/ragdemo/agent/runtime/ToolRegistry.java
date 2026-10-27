package com.example.ragdemo.agent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.example.ragdemo.agent.tools.AgentToolkitFactory.AgentToolkit;
import com.example.ragdemo.agent.tools.PaperToolException;
import com.example.ragdemo.agent.tools.model.ToolErrorCode;
import com.example.ragdemo.agent.tools.model.ToolResult;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

/**
 * 工具<b>白名单注册表</b>（Step 3 §4）。
 * <p>
 * <ul>
 *   <li>Spec 用 0.36.2 实测 API {@link ToolSpecifications#toolSpecificationsFrom(Object)} 逐个工具对象收集，
 *       按 {@code name} 建立唯一索引；启动前断言恰好 {@value #EXPECTED_CALLABLE_COUNT} 个 callable。</li>
 *   <li>执行<b>不做任意反射</b>：每个 callable 绑定一个显式 lambda（编译期确定的方法调用），
 *       模型只能命中白名单里的名字，无法让它反射调用别的 Java 方法。</li>
 *   <li>注册表由本次 Run 的 <b>Toolkit 实例</b>构建 ⇒ 工具天然绑定该 Run 的知识库范围，
 *       跨 Run 复用同一个注册表/工具对象是不可能的（§8.1.6）。</li>
 *   <li>参数校验见 {@link ToolArgs}；异常一律收敛为结构化 {@link ToolError}。</li>
 * </ul>
 */
public final class ToolRegistry {

    /** Step 2 六工具共 7 个 callable 方法（readSection 与 getStructure 同属一个工具类）。 */
    public static final int EXPECTED_CALLABLE_COUNT = 7;

    /** 单个 callable 的绑定：spec + 显式执行器。 */
    public record ToolBinding(String name, ToolSpecification specification,
            Function<ToolArgs, ToolResult<?>> invoker) {
    }

    private final Map<String, ToolBinding> bindings;
    private final List<ToolSpecification> specifications;

    private ToolRegistry(Map<String, ToolBinding> bindings) {
        this.bindings = Map.copyOf(bindings);
        this.specifications = bindings.values().stream().map(ToolBinding::specification).toList();
    }

    public static ToolRegistry forToolkit(AgentToolkit toolkit) {
        Map<String, ToolBinding> bindings = new LinkedHashMap<>();

        register(bindings, spec(toolkit.searchPaperTool(), "search"),
                args -> toolkit.searchPaperTool().search(args.requireString("query"), args.optionalInt("topK")));

        register(bindings, spec(toolkit.listPapersTool(), "listPapers"),
                args -> toolkit.listPapersTool().listPapers(args.optionalString("titleContains"),
                        args.optionalString("status"), args.optionalString("parseMode"),
                        args.optionalInt("pageSize"), args.optionalInt("offset")));

        register(bindings, spec(toolkit.getPaperStructureTool(), "getStructure"),
                args -> toolkit.getPaperStructureTool().getStructure(args.requireLong("documentId")));

        register(bindings, spec(toolkit.getPaperStructureTool(), "readSection"),
                args -> toolkit.getPaperStructureTool().readSection(args.requireLong("documentId"),
                        args.requireString("sectionId"), args.optionalInt("maxChars")));

        register(bindings, spec(toolkit.getOriginalPdfTool(), "getOriginalPdf"),
                args -> toolkit.getOriginalPdfTool().getOriginalPdf(args.requireLong("documentId")));

        register(bindings, spec(toolkit.readPdfPagesTool(), "readPdfPages"),
                args -> toolkit.readPdfPagesTool().readPdfPages(args.requireString("pdfHandle"),
                        args.optionalInt("startPage"), args.optionalInt("endPage"),
                        args.optionalBoolean("includePageImage")));

        register(bindings, spec(toolkit.inspectPaperEvidenceTool(), "inspectEvidence"),
                args -> toolkit.inspectPaperEvidenceTool().inspectEvidence(args.optionalLong("documentId"),
                        args.optionalString("pdfHandle"), args.requireString("targetType"),
                        args.requireString("target"), args.optionalInt("pageNumber")));

        if (bindings.size() != EXPECTED_CALLABLE_COUNT) {
            throw new IllegalStateException("工具注册数量异常：期望 " + EXPECTED_CALLABLE_COUNT
                    + " 个 callable，实际 " + bindings.size() + " 个: " + bindings.keySet());
        }
        return new ToolRegistry(bindings);
    }

    private static void register(Map<String, ToolBinding> bindings, ToolSpecification specification,
            Function<ToolArgs, ToolResult<?>> invoker) {
        if (bindings.putIfAbsent(specification.name(), new ToolBinding(specification.name(), specification,
                invoker)) != null) {
            throw new IllegalStateException("工具名冲突: " + specification.name());
        }
    }

    /** 取工具对象上指定方法名的 spec（名字不匹配即启动失败，避免静默错配）。 */
    private static ToolSpecification spec(Object toolObject, String methodName) {
        return ToolSpecifications.toolSpecificationsFrom(toolObject).stream()
                .filter(specification -> specification.name().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("工具方法未找到: " + methodName
                        + "（可发现: " + ToolSpecifications.toolSpecificationsFrom(toolObject).stream()
                                .map(ToolSpecification::name).toList() + "）"));
    }

    public List<ToolSpecification> specifications() {
        return specifications;
    }

    public Set<String> toolNames() {
        return bindings.keySet();
    }

    public int size() {
        return bindings.size();
    }

    public boolean supports(String toolName) {
        return bindings.containsKey(toolName);
    }

    /**
     * 执行一次工具调用（白名单内）。永不抛出：所有异常都变成结构化失败结果。
     */
    public ToolResult<?> invoke(String toolName, String argumentsJson) {
        ToolBinding binding = bindings.get(toolName);
        if (binding == null) {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT,
                    "未知工具（不在白名单）: " + sanitizeName(toolName));
        }
        try {
            ToolArgs args = ToolArgs.parse(argumentsJson);
            ToolResult<?> result = binding.invoker().apply(args);
            return result == null
                    ? ToolResult.fail(ToolErrorCode.INTERNAL_ERROR, "工具未返回结果")
                    : result;
        } catch (PaperToolException e) {
            return ToolResult.fail(e.toError());
        } catch (RuntimeException e) {
            return ToolResult.fail(ToolErrorCode.INTERNAL_ERROR,
                    "工具执行失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    /** 只保留安全字符并截断，避免把模型给的任意文本回显/写日志。 */
    private static String sanitizeName(String toolName) {
        if (toolName == null) {
            return "<null>";
        }
        String cleaned = toolName.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
