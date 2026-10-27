package com.example.ragdemo.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 可靠消费配置（Step 5 §6）。
 * <p>
 * <b>为什么需要</b>：审计发现原实现只有裸 {@code @KafkaListener} —— 没有 error handler、
 * 没有 DLT、没有显式 ack 语义；一旦监听器抛异常会无限重投（热循环），且没有任何对账出口。
 * <p>
 * 本配置提供三件事：
 * <ol>
 *   <li>有限重试（2 次、固定 1s 间隔）后把消息投递到 <b>DLT</b>，避免毒消息热循环；</li>
 *   <li>显式声明 DLT topic（新增、非破坏性）；</li>
 *   <li>（配合 application.yml）{@code ack-mode: record} + {@code enable-auto-commit: false}，
 *       使 ack 边界落在<b>监听方法成功返回之后</b>。</li>
 * </ol>
 * <b>注意</b>：业务失败（例如文档不存在、canonical hard-invalid）在 {@code IngestionService} 内部
 * 已被捕获并写入 {@code documents/ingestion_tasks} 终态，<b>不会</b>抛出到监听器 ——
 * 因此 DLT 只用于"监听器本身抛出"的异常（反序列化/基础设施类）。数据库仍是任务的权威状态来源。
 * <p>
 * 开关：{@code rag.kafka.reliability.enabled}（<b>默认关闭</b>，需显式设为 true）。
 * 之所以默认关闭：在真实 broker 上的"重复投递幂等"验收**未通过**
 * （消费者已订阅分区但未收到记录），按"未验收不得改变生产行为"原则保持旧行为不变。
 */
@Configuration
@ConditionalOnProperty(name = "rag.kafka.reliability.enabled", havingValue = "true")
public class KafkaReliabilityConfig {

    /** 死信 topic：与主 topic 同源，便于对账与人工排查。 */
    public static final String DLT_TOPIC = "rag-document-ingestion.DLT";

    @Bean
    public NewTopic documentIngestionDltTopic() {
        return TopicBuilder.name(DLT_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaDefaultErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> new TopicPartition(DLT_TOPIC, 0));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
    }
}
