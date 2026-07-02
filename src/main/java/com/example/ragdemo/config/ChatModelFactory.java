package com.example.ragdemo.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties.ModelConfig;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

@Component
public class ChatModelFactory {
    private final RagProperties ragProperties;
    private final Map<String, ChatLanguageModel> cache = new ConcurrentHashMap<>();

    public ChatModelFactory(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public ChatLanguageModel getModel(String modelKey) {
        String key = (modelKey == null || modelKey.isBlank()) ? ragProperties.getDefaultModel() : modelKey;
        return cache.computeIfAbsent(key, k -> buildModel(ragProperties.getModels().get(key)));
    }

    private ChatLanguageModel buildModel(ModelConfig modelConfig) {
        return switch (modelConfig.getProvider()) {
            case "openai-compatible" -> OpenAiChatModel.builder()
                    .baseUrl(modelConfig.getBaseUrl())
                    .apiKey(modelConfig.getApiKey())
                    .modelName(modelConfig.getModelName())
                    .temperature(modelConfig.getTemperature())
                    .maxTokens(modelConfig.getMaxOutputTokens())
                    .build();
            default -> throw new IllegalArgumentException("不支持的Provider：" + modelConfig.getProvider());
        };
    }
}
