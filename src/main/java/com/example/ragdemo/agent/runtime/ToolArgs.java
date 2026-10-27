package com.example.ragdemo.agent.runtime;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import com.example.ragdemo.agent.tools.PaperToolException;

/**
 * 工具参数解析与<b>严格校验</b>（Step 3 §4）。
 * <p>
 * 输入是模型给出的原始 JSON 文本：必须按类型取值、必填缺一即拒、类型不符即拒、
 * 数字容忍 "5" 这类字符串形式（模型常见），但绝不接受超范围/畸形结构。
 * 任何拒绝都抛 {@link PaperToolException#invalidArgument} ⇒ 由 Registry 转成结构化 ToolError，
 * 不向模型泄漏堆栈/SQL/内部路径。
 */
public final class ToolArgs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;
    private final String raw;

    private ToolArgs(JsonNode root, String raw) {
        this.root = root;
        this.raw = raw;
    }

    public static ToolArgs parse(String json) {
        if (json == null || json.isBlank()) {
            return new ToolArgs(MissingNode.getInstance(), "");
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                throw PaperToolException.invalidArgument("工具参数必须是 JSON 对象");
            }
            return new ToolArgs(node, json);
        } catch (JsonProcessingException e) {
            throw PaperToolException.invalidArgument("工具参数不是合法 JSON");
        }
    }

    public String raw() {
        return raw;
    }

    public boolean has(String name) {
        JsonNode node = root.get(name);
        return node != null && !node.isNull();
    }

    public String requireString(String name) {
        String value = optionalString(name);
        if (value == null) {
            throw PaperToolException.invalidArgument("缺少必填参数: " + name);
        }
        return value;
    }

    public String optionalString(String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            return text.isEmpty() ? null : text;
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        throw PaperToolException.invalidArgument("参数类型错误: " + name + " 应为字符串");
    }

    public Long optionalLong(String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && node.asText().trim().matches("-?\\d+")) {
            return Long.parseLong(node.asText().trim());
        }
        throw PaperToolException.invalidArgument("参数类型错误: " + name + " 应为整数");
    }

    public Long requireLong(String name) {
        Long value = optionalLong(name);
        if (value == null) {
            throw PaperToolException.invalidArgument("缺少必填参数: " + name);
        }
        return value;
    }

    public Integer optionalInt(String name) {
        Long value = optionalLong(name);
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw PaperToolException.invalidArgument("参数超出整数范围: " + name);
        }
        return value.intValue();
    }

    public Boolean optionalBoolean(String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String text = node.asText().trim().toLowerCase();
            if ("true".equals(text) || "false".equals(text)) {
                return Boolean.parseBoolean(text);
            }
        }
        throw PaperToolException.invalidArgument("参数类型错误: " + name + " 应为布尔值");
    }

    /** 未识别的多余参数（用于日志/告警，不直接拒绝，因为模型可能补默认值）。 */
    public List<String> extraKeys(List<String> known) {
        List<String> extras = new ArrayList<>();
        root.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                extras.add(name);
            }
        });
        return extras;
    }
}
