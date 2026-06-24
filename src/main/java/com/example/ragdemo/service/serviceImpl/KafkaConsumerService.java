package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.model.dto.DocumentIngestionMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class KafkaConsumerService {

    private final IngestionService ingestionService;

    public KafkaConsumerService(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @KafkaListener(topics = "rag-document-ingestion", groupId = "rag-ingestion-group")
    public void consume(DocumentIngestionMessage message) {
    }
}
