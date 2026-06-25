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

    @KafkaListener(topics = "rag-document-ingestion", groupId = "rag-ingestion-group")
    public void consume(DocumentIngestionMessage message) {
        log.info("收到入库消息: documentId={}, taskId={}", message.documentId(), message.taskId());
        ingestionService.ingest(message.documentId(), message.taskId());
    }
}
