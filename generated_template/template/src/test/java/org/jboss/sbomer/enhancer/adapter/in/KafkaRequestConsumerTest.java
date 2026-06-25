package org.jboss.sbomer.enhancer.adapter.in;

import static org.mockito.Mockito.lenient;
import static org.jboss.sbomer.enhancer.core.ApplicationConstants.COMPONENT_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.jboss.sbomer.enhancer.core.port.api.EnhancementOrchestrator;
import org.jboss.sbomer.enhancer.core.port.spi.FailureNotifier;
import org.jboss.sbomer.events.orchestration.EnhancementCreated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KafkaRequestConsumerTest {

    @Mock
    EnhancementOrchestrator orchestrator;

    @Mock
    FailureNotifier failureNotifier;

    @InjectMocks
    KafkaRequestConsumer consumer;

    @Test
    void testReceive_Success_WhenIsMyEnhancer() {
        // Arrange
        EnhancementCreated mockEvent = createMockEvent(COMPONENT_NAME);

        // Act
        consumer.receive(mockEvent);

        // Assert
        verify(orchestrator).acceptRequest(
                eq("E-123"),
                eq("G-456"),
                eq("CORR-999"), // <-- Added the 5th parameter for Correlation ID
                any(Map.class),
                any(List.class)
        );
        verify(failureNotifier, never()).notify(any(), any(), any());
    }

    @Test
    void testReceive_Ignored_WhenNotMyEnhancer() {
        // Arrange
        EnhancementCreated mockEvent = createMockEvent("some-other-enhancer");

        // Act
        consumer.receive(mockEvent);

        // Assert
        // The orchestrator should never be called if the component name doesn't match (Updated to 5 any() arguments)
        verify(orchestrator, never()).acceptRequest(any(), any(), any(), any(), any());
        verify(failureNotifier, never()).notify(any(), any(), any());
    }

    @Test
    void testReceive_Ignored_WhenDataIsNull() {
        // Arrange
        // Deep stubs allow us to mock event.getContext().getEventId() without knowing the Context class
        EnhancementCreated mockEvent = mock(EnhancementCreated.class, Answers.RETURNS_DEEP_STUBS);
        when(mockEvent.getContext().getEventId()).thenReturn("EVENT-123");
        when(mockEvent.getData()).thenReturn(null);

        // Act
        consumer.receive(mockEvent);

        // Assert
        // Updated to 5 any() arguments
        verify(orchestrator, never()).acceptRequest(any(), any(), any(), any(), any());
        verify(failureNotifier, never()).notify(any(), any(), any());
    }

    @Test
    void testReceive_HandlesException_WhenOrchestratorFails() {
        // Arrange
        EnhancementCreated mockEvent = createMockEvent(COMPONENT_NAME);

        // Force the orchestrator to throw an unexpected exception (Updated to 5 any() arguments)
        org.mockito.Mockito.doThrow(new RuntimeException("Database down"))
                .when(orchestrator).acceptRequest(any(), any(), any(), any(), any());

        // Act
        consumer.receive(mockEvent);

        // Assert
        // The consumer loop should not crash, and the failure notifier should catch it
        verify(failureNotifier).notify(any(), eq("CORR-999"), eq(mockEvent));
    }

    @Test
    void testReceive_HandlesNullEventSafely() {
        // Act
        // Calling receive with a completely null event will throw an NPE inside the try block
        consumer.receive(null);

        // Assert
        // The catch block should handle the null event branch properly
        verify(failureNotifier).notify(any(), isNull(), isNull());
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    /**
     * Helper method to deeply mock the Avro event hierarchy using RETURNS_DEEP_STUBS.
     */
    private EnhancementCreated createMockEvent(String targetEnhancerName) {
        EnhancementCreated event = mock(EnhancementCreated.class, Answers.RETURNS_DEEP_STUBS);

        // Use lenient() because not every test will read every field from this dummy object
        lenient().when(event.getContext().getEventId()).thenReturn("EVENT-123");
        lenient().when(event.getContext().getCorrelationId()).thenReturn("CORR-999");
        lenient().when(event.getData().getEnhancementId()).thenReturn("E-123");
        lenient().when(event.getData().getGenerationId()).thenReturn("G-456");
        lenient().when(event.getData().getInputSbomUrls()).thenReturn(List.of("http://storage/bom.json"));
        lenient().when(event.getData().getEnhancer().getName()).thenReturn(targetEnhancerName);
        lenient().when(event.getData().getEnhancer().getOptions()).thenReturn(Map.of("key", "value"));

        return event;
    }
}