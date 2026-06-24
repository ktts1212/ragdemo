package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.model.dto.DocumentIngestionMessage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private static final String TOPIC = "rag-document-ingestion";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendDocumentIngestionTask(DocumentIngestionMessage message) {
    }
}
