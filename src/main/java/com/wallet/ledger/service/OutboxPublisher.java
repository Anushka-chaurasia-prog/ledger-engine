package com.wallet.ledger.service;

import com.wallet.ledger.domain.OutboxEvent;
import com.wallet.ledger.domain.OutboxStatus;
import com.wallet.ledger.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Component
public class OutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String TOPIC = "ledger.transfers.posted";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        // Find bounded batch of events
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        
        for (OutboxEvent event : pendingEvents) {
            String transactionId = event.getTransaction().getId().toString();
            String payload = event.getPayload();
            
            logger.info("Picked PENDING event for transaction {}. Publishing to Kafka...", transactionId);
            
            try {
                // Publish to Kafka
                CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(TOPIC, transactionId, payload);
                
                // Wait for broker acknowledgement
                future.get();
                
                logger.info("Successfully published event for transaction {} to Kafka", transactionId);
                
                // Mark SENT (save creates a tiny, separate transaction because this method isn't @Transactional)
                event.setStatus(OutboxStatus.SENT);
                outboxEventRepository.save(event);
                
            } catch (ExecutionException e) {
                logger.error("Kafka publication failed for transaction {}. Event remains PENDING.", transactionId, e.getCause());
                // Break out of the loop to backoff and let the next scheduler execution retry,
                // or continue? Typically continuing is fine, but if Kafka is down, it's better to break.
                break;
            } catch (InterruptedException e) {
                logger.error("Kafka publication interrupted for transaction {}. Event remains PENDING.", transactionId, e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Unexpected error publishing event for transaction {}. Event remains PENDING.", transactionId, e);
                break;
            }
        }
    }
}
