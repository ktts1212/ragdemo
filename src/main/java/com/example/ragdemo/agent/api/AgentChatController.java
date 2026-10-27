package com.example.ragdemo.agent.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ragdemo.agent.model.AgentFinishReason;
import com.example.ragdemo.agent.runtime.AgentRunResult;
import com.example.ragdemo.agent.runtime.RagAgentService;

/**
 * 单 Agent API（Step 3 §7）。
 * <p>
 * 与旧 {@code /api/chat} <b>完全独立</b>：不改其契约、不共用会话记忆、不共用 threadId。
 * 默认（{@code rag.agent.enabled=false}）返回 503 {@code AGENT_DISABLED}，且不调用 LLM/工具。
 * 响应体直接是 {@link AgentRunResult}（含 answer/sources/usedTools/finishReason/warnings/trace/handles）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private final RagAgentService agentService;

    public AgentChatController(RagAgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public ResponseEntity<Object> chat(@RequestBody(required = false) AgentChatRequestDto request) {
        if (request == null) {
            return error(400, "INVALID_ARGUMENT", "请求体不能为空");
        }
        try {
            AgentRunResult result = agentService.run(request.knowledgeBaseId(), request.conversationId(),
                    request.question(), request.requestKey());
            if (AgentFinishReason.DISABLED.name().equals(result.finishReason())) {
                return error(503, "AGENT_DISABLED", result.answer());
            }
            return ResponseEntity.ok(result);
        } catch (AgentRequestException e) {
            return error(e.status(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            // 不向调用方暴露堆栈/内部细节
            log.error("[agent] API 执行失败: type={}", e.getClass().getSimpleName());
            return error(500, "AGENT_ERROR", "Agent 执行失败（已记录日志）");
        }
    }

    private ResponseEntity<Object> error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }
}
