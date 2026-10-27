package com.example.ragdemo.memory.l3;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * L3 检索路由（Memory V1 · Phase 3，V1 规则实现，不调用 LLM）。
 *
 * <p>目的：**不是每轮都查长期记忆**。只有"历史技术决策 / 长期偏好 / 用户或项目背景 / 既往研究计划"
 * 这类跨会话信息才触发 L3；论文指标、章节内容、实验结果等默认走文献检索（L1/L2 + KB）。
 *
 * <p>显式请求（"根据记忆""参考历史"等，或调用方传入 {@code explicitRequest}）优先触发。
 * 规则是确定性的，便于审计与单测。
 */
@Component
public class MemoryRetrievalRouter {

    public record Decision(boolean useMemory, String reason) {
    }

    /** 显式要求使用记忆的表述。 */
    private static final List<String> EXPLICIT = List.of(
            "根据记忆", "参考记忆", "用记忆", "记忆里", "长期记忆", "历史记忆", "参考历史", "结合历史",
            "你还记得", "你记得", "之前告诉过你", "remember", "from memory");

    /** 跨会话意图（历史决策/偏好/背景/计划）。 */
    private static final List<String> HISTORY_INTENT = List.of(
            "之前", "上次", "上回", "此前", "历史", "以前", "早先", "我们定过", "我们决定", "我们选的",
            "我们的决定", "我们的约定", "已经确定", "我的偏好", "我偏好", "我习惯", "我喜欢",
            "研究计划", "之前的计划", "继续上次", "接着上次", "延续之前", "previously", "earlier",
            "last time", "we decided", "my preference", "our plan", "本项目", "这个项目", "偏好");

    /** 需要容忍少量连接词的说法（"这个项目**的**背景" / "我的**长期**偏好"）。 */
    private static final List<Pattern> HISTORY_PATTERNS = List.of(
            Pattern.compile("项目.{0,4}背景"),
            Pattern.compile("研究.{0,4}计划"),
            Pattern.compile("(我|用户).{0,3}(偏好|习惯)"));

    /** 典型文献检索意图：即使句子较长，也不因单个模糊词触发 L3。 */
    private static final List<String> LITERATURE_INTENT = List.of(
            "论文", "章节", "第几章", "实验", "指标", "数据集", "doi", "表格", "figure", "table",
            "方法", "结果", "性能", "精度", "召回率", "f1", "baseline", "消融", "公式", "作者", "页码");

    public Decision route(String question) {
        return route(question, false);
    }

    /**
     * @param explicitRequest 调用方（如 HTTP 参数/显式命令）要求使用记忆
     */
    public Decision route(String question, boolean explicitRequest) {
        if (explicitRequest) {
            return new Decision(true, "EXPLICIT_REQUEST");
        }
        if (question == null || question.isBlank()) {
            return new Decision(false, "EMPTY_QUESTION");
        }
        String text = question.toLowerCase(Locale.ROOT);
        if (containsAny(text, EXPLICIT)) {
            return new Decision(true, "EXPLICIT_PHRASE");
        }
        // 文献问题默认走检索（规范要求），即使句子里含"之前/背景"等模糊词
        if (containsAny(text, LITERATURE_INTENT)) {
            return new Decision(false, "LITERATURE_DEFAULT");
        }
        if (containsAny(text, HISTORY_INTENT) || HISTORY_PATTERNS.stream().anyMatch(p -> p.matcher(text).find())) {
            return new Decision(true, "HISTORY_REFERENCE");
        }
        return new Decision(false, "NO_MEMORY_SIGNAL");
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
