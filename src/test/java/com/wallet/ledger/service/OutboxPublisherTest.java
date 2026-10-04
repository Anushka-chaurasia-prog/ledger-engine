package com.wallet.ledger.service;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.domain.OutboxEvent;
import com.wallet.ledger.domain.OutboxStatus;
import com.wallet.ledger.dto.TransferRequest;
import com.wallet.ledger.repository.AccountRepository;
import com.wallet.ledger.repository.LedgerEntryRepository;
import com.wallet.ledger.repository.LedgerTransactionRepository;
import com.wallet.ledger.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        // Disable real scheduler to test it manually
        "spring.main.allow-bean-definition-overriding=true"
})
public class OutboxPublisherTest {

    @MockBean(name = "kafkaTemplate")
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReversalService reversalService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SystemWalletInitializer systemWalletInitializer;

    private UUID accountA;
    private UUID accountB;

    @Autowired
    private com.wallet.ledger.service.DepositService depositService;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        systemWalletInitializer.initSystemWallet();

        Account a = accountRepository.save(new Account("INR"));
        Account b = accountRepository.save(new Account("INR"));
        accountA = a.getId();
        accountB = b.getId();

        // Give accountA some money
        depositService.deposit(accountA, new com.wallet.ledger.dto.DepositRequest(10000L));
        depositService.deposit(accountB, new com.wallet.ledger.dto.DepositRequest(10000L));
        
        // delete outbox created by setup
        outboxEventRepository.deleteAll();
    }

    @Test
    void test1_PendingEventExistsAfterTransfer() {
        transferService.transfer(new TransferRequest(accountA, accountB, 500L), UUID.randomUUID().toString());

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        OutboxEvent event = outboxEvents.get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getEventType()).isEqualTo("TRANSFER_COMPLETED");
    }

    @Test
    void test2_PendingEventExistsAfterReversal() {
        var transferRes = transferService.transfer(new TransferRequest(accountA, accountB, 500L), UUID.randomUUID().toString());
        reversalService.reverse(transferRes.getTransactionId());

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(2);
        
        OutboxEvent reversalEvent = outboxEvents.stream()
                .filter(e -> e.getEventType().equals("TRANSFER_REVERSED"))
                .findFirst().get();

        assertThat(reversalEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void test3_SuccessfulKafkaPublication() throws Exception {
        transferService.transfer(new TransferRequest(accountA, accountB, 500L), UUID.randomUUID().toString());
        OutboxEvent pendingEvent = outboxEventRepository.findAll().get(0);

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(future);

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate, times(1)).send(Mockito.anyString(), keyCaptor.capture(), valueCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo(pendingEvent.getTransaction().getId().toString());
        assertThat(valueCaptor.getValue()).isEqualTo(pendingEvent.getPayload());

        OutboxEvent updatedEvent = outboxEventRepository.findById(pendingEvent.getId()).get();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void test4_KafkaFailure() {
        transferService.transfer(new TransferRequest(accountA, accountB, 500L), UUID.randomUUID().toString());
        OutboxEvent pendingEvent = outboxEventRepository.findAll().get(0);

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Simulated Kafka Failure"));
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(future);

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, times(1)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());

        OutboxEvent updatedEvent = outboxEventRepository.findById(pendingEvent.getId()).get();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void test5_MultiplePendingEvents() {
        for (int i = 0; i < 5; i++) {
            transferService.transfer(new TransferRequest(accountA, accountB, 100L), UUID.randomUUID().toString());
        }

        assertThat(outboxEventRepository.findAll()).hasSize(5);

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(future);

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, times(5)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());

        List<OutboxEvent> afterEvents = outboxEventRepository.findAll();
        for (OutboxEvent event : afterEvents) {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        }
    }

    @Test
    void test6_BatchLimit() {
        // We only mock the repository to return a large number of events 
        // to show that the publisher would respect it if we could, 
        // but since we rely on actual postgres, let's insert 105 events and run it.
        // Wait, inserting 105 real transfers is slow.
        // I will just verify the publisher works correctly for limits if needed, 
        // but it reads 100 per execution.
        
        for (int i = 0; i < 105; i++) {
            transferService.transfer(new TransferRequest(accountA, accountB, 1L), UUID.randomUUID().toString());
        }

        assertThat(outboxEventRepository.findAll()).hasSize(105);

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(future);

        // One execution processes max 100
        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, times(100)).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());

        long sentCount = outboxEventRepository.findAll().stream().filter(e -> e.getStatus() == OutboxStatus.SENT).count();
        long pendingCount = outboxEventRepository.findAll().stream().filter(e -> e.getStatus() == OutboxStatus.PENDING).count();

        assertThat(sentCount).isEqualTo(100);
        assertThat(pendingCount).isEqualTo(5);
    }
}
