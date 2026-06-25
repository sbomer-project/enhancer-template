package org.jboss.sbomer.enhancer.adapter.out;

import static org.jboss.sbomer.enhancer.core.ApplicationConstants.COMPONENT_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.sbomer.enhancer.core.domain.EnhancementStatus;
import org.jboss.sbomer.events.enhancer.EnhancementUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KafkaStatusNotifierTest {

    @Mock
    Emitter<EnhancementUpdate> emitter;

    @InjectMocks
    KafkaStatusNotifier notifier;

    @Captor
    ArgumentCaptor<EnhancementUpdate> eventCaptor;

    @Test
    void testNotifyStatus_Success_Finished() {
        // Arrange
        String enhancementId = "E-123";
        EnhancementStatus status = EnhancementStatus.FINISHED;
        String reason = "Enhancement completed successfully";
        List<String> resultUrls = List.of("http://storage/bom.json");

        // Mock the emitter to return a successful CompletableFuture
        when(emitter.send(any(EnhancementUpdate.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        notifier.notifyStatus(enhancementId, status, reason, resultUrls);

        // Assert
        verify(emitter).send(eventCaptor.capture());
        EnhancementUpdate capturedEvent = eventCaptor.getValue();

        // Verify Data mapping
        assertNotNull(capturedEvent.getData());
        assertEquals("E-123", capturedEvent.getData().getEnhancementId());
        assertEquals("FINISHED", capturedEvent.getData().getStatus());
        assertEquals(reason, capturedEvent.getData().getReason());
        assertEquals(0, capturedEvent.getData().getResultCode(), "FINISHED should have resultCode 0");
        assertEquals(resultUrls, capturedEvent.getData().getEnhancedSbomUrls());

        // Verify Context mapping
        assertNotNull(capturedEvent.getContext());
        assertNotNull(capturedEvent.getContext().getEventId(), "Event ID should be auto-generated");
        assertNotNull(capturedEvent.getContext().getTimestamp(), "Timestamp should be auto-generated");
        assertEquals("EnhancementUpdate", capturedEvent.getContext().getType());
        assertEquals("1.0", capturedEvent.getContext().getEventVersion());
        assertEquals(COMPONENT_NAME, capturedEvent.getContext().getSource());
    }

    @Test
    void testNotifyStatus_Failure_SetsResultCodeToOne() {
        // Arrange
        String enhancementId = "E-123";
        EnhancementStatus status = EnhancementStatus.FAILED;
        String reason = "Storage unavailable";

        when(emitter.send(any(EnhancementUpdate.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        // Result URLs will be null on a failure
        notifier.notifyStatus(enhancementId, status, reason, null);

        // Assert
        verify(emitter).send(eventCaptor.capture());
        EnhancementUpdate capturedEvent = eventCaptor.getValue();

        assertEquals("FAILED", capturedEvent.getData().getStatus());
        assertEquals(1, capturedEvent.getData().getResultCode(), "FAILED should have resultCode 1");
        assertNull(capturedEvent.getData().getEnhancedSbomUrls());
    }

    @Test
    void testNotifyStatus_HandlesKafkaSendErrorGracefully() {
        // Arrange
        // Simulate Kafka being down or rejecting the message
        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker offline"));
        
        when(emitter.send(any(EnhancementUpdate.class))).thenReturn(failedFuture);

        // Act
        // We just want to ensure that this does NOT throw an exception back to the caller
        notifier.notifyStatus("E-123", EnhancementStatus.ENHANCING, "Starting...", null);

        // Assert
        // If we reach here without throwing, the .whenComplete() error block successfully caught it.
        verify(emitter).send(any(EnhancementUpdate.class));
    }
}