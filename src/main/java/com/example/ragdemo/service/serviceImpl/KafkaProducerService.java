package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.model.dto.DocumentIngestionMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaProducerService {

    private static final String TOPIC = "rag-document-ingestion";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendDocumentIngestionTask(DocumentIngestionMessage message) {
        kafkaTemplate.send(TOPIC, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 发送失败: documentId={}", message.documentId(), ex);
                    } else {
                        log.info("Kafka 发送成功: documentId={}, offset={}",
                                message.documentId(), result.getRecordMetadata().offset());
                    }
                });
    }
}
