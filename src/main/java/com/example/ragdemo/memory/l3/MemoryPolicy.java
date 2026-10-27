package com.example.ragdemo.memory.l3;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 长期记忆写入策略（Memory V1 · Phase 2B）。
 * <p>**LLM 只能提出候选**；是否允许写入由本类（代码规则）决定，模型无法指定 principalId、作用域或绕过策略。
 * <p>规则：
 * <ol>
 *   <li>类型白名单（FACT/PREFERENCE/DECISION）；</li>
 *   <li>内容长度上限（超限**拒绝**而非截断）；</li>
 *   <li>敏感信息默认不保存（邮箱/电话/身份证/密钥/密码/令牌）；</li>
 *   <li>Prompt Injection 形态（"忽略之前的指令"、角色劫持、"system:"）⇒ 拒绝；</li>
 *   <li>低价值内容（寒暄/纯提问/过短）⇒ 拒绝；</li>
 *   <li>来源必须可信：调用方只能传入"已提交的用户消息"，且不能包含工具输出标记。</li>
 * </ol>
 */
@Slf4j
@Component
public class MemoryPolicy {

    public enum Decision {
        ALLOW, REJECT
    }

    public record Result(Decision decision, String code) {
        static Result allow() {
            return new Result(Decision.ALLOW, "OK");
        }

        static Result reject(String code) {
            return new Result(Decision.REJECT, code);
        }
    }

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})"      // email
                    + "|(\\b1[3-9]\\d{9}\\b)"                             // 大陆手机号
                    + "|(\\b\\d{17}[\\dXx]\\b)"                           // 身份证
                    + "|(?i)(sk-[A-Za-z0-9]{8,})"                         // API key
                    + "|(?i)(password|passwd|密码|口令)\\s*[:=]"           // 密码
                    + "|(?i)(bearer\\s+[A-Za-z0-9._-]{10,})");            // token

    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(忽略(之前|以上|前面)的?(指令|提示)|ignore\\s+(all\\s+)?previous"
                    + "|你现在是|you\\s+are\\s+now|system\\s*:|<\\|im_start\\|>)");

    private static final List<String> GREETINGS = List.of(
            "你好", "您好", "hi", "hello", "谢谢", "thanks", "在吗", "早上好", "晚安");

    private final MemoryProperties properties;

    private static Result allow() {
        return new Result(Decision.ALLOW, "OK");
    }

    private static Result reject(String code) {
        return new Result(Decision.REJECT, code);
    }

    public MemoryPolicy(MemoryProperties properties) {
        this.properties = properties;
    }

    /** 审核一条候选；不可信来源直接拒绝（sourceText 由 Processor 传入**已提交用户消息原文**）。 */
    public Result evaluate(MemoryCandidate candidate, String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return reject("UNTRUSTED_SOURCE");
        }
        if (sourceText.startsWith("[UNTRUSTED_TOOL_OUTPUT") || sourceText.contains("UNTRUSTED_TOOL_OUTPUT")) {
            return reject("UNTRUSTED_SOURCE"); // 工具输出/论文片段不得成为用户长期记忆
        }
        if (!MemoryCandidate.TYPES.contains(candidate.type())) {
            return reject("ILLEGAL_TYPE");
        }
        int max = Math.max(50, properties.getL3().getMaxContentChars());
        if (candidate.content().length() > max) {
            return reject("CONTENT_TOO_LONG");
        }
        if (SENSITIVE.matcher(candidate.content()).find()) {
            return reject("SENSITIVE_DATA");
        }
        if (INJECTION.matcher(candidate.content()).find() || INJECTION.matcher(candidate.subject()).find()) {
            return reject("PROMPT_INJECTION");
        }
        String content = candidate.content().trim();
        if (content.length() < 8) {
            return reject("LOW_VALUE");
        }
        String lowered = content.toLowerCase(Locale.ROOT);
        if (GREETINGS.stream().anyMatch(lowered::equals)) {
            return reject("LOW_VALUE");
        }
        if (content.endsWith("?") || content.endsWith("？")) {
            return reject("LOW_VALUE"); // 纯提问不是长期事实
        }
        if (candidate.confidence() < 0.5d) {
            return reject("LOW_CONFIDENCE");
        }
        log.debug("[l3-policy] allow: type={}, subject={}", candidate.type(), candidate.subject());
        return Result.allow();
    }
}
