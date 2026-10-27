package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.model.dto.DocumentIngestionMessage;
import com.example.ragdemo.service.IngestionService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaConsumerService {

    private final IngestionService ingestionService;

    public KafkaConsumerService(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * 入库消息监听。
     * <p>
     * group-id 允许通过 {@code rag.kafka.consumer-group-id} 覆盖（默认值保持 {@code rag-ingestion-group}
     * ⇒ 生产行为不变）。之所以可配：崩溃恢复/幂等等场景的自动化验收需要用<b>独立 group</b>
     * 从 latest 起消费，避免被历史积压消息干扰（那些消息属于另一个 group 的进度）。
     */
    @KafkaListener(topics = "rag-document-ingestion",
            groupId = "${rag.kafka.consumer-group-id:rag-ingestion-group}")
    public void consume(DocumentIngestionMessage message) {
        log.info("收到入库消息: documentId={}, taskId={}", message.documentId(), message.taskId());
        ingestionService.ingest(message.documentId(), message.taskId());
    }
}
