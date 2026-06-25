package org.jboss.sbomer.enhancer.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.cyclonedx.model.Bom;
import org.jboss.sbomer.enhancer.core.domain.EnhancementStatus;
import org.jboss.sbomer.enhancer.core.port.spi.FailureNotifier;
import org.jboss.sbomer.enhancer.core.port.spi.SBOMStorage;
import org.jboss.sbomer.enhancer.core.port.spi.StatusNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnhancerServiceTest {

    @Mock
    SBOMStorage sbomStorage;

    @Mock
    StatusNotifier statusNotifier;

    @Mock
    FailureNotifier failureNotifier;

    @InjectMocks
    EnhancerService enhancerService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        enhancerService.toolName = "SBOMer";
        enhancerService.toolVersion = "1.0.0";
        enhancerService.supplierName = "Red Hat";
        enhancerService.supplierUrls = List.of("https://www.redhat.com");
    }

    @Test
    void testAcceptRequest_Success() throws Exception {
        // Arrange
        String enhancementId = "E-123";
        String generationId = "G-456";
        String correlationId = "CORR-999";
        List<String> inputUrls = List.of("http://internal-storage/api/v1/storage/content/G-456/bom.json");

        // Create a fake downloaded BOM file
        Path mockDownloadedFile = tempDir.resolve("downloaded-bom.json");
        String minimalBom = "{ \"bomFormat\": \"CycloneDX\", \"specVersion\": \"1.6\", \"version\": 1 }";
        Files.writeString(mockDownloadedFile, minimalBom);

        // Mock the storage download to return our extracted path and the fake file
        when(sbomStorage.downloadSbom("G-456/bom.json")).thenReturn(mockDownloadedFile);

        // Mock the upload to return a fake permanent URL AND intercept the file to verify the CycloneDX injection
        doAnswer(invocation -> {
            Path fileToUpload = invocation.getArgument(2);

            // 1. Parse the modified file back into a CycloneDX object for safe structural testing
            org.cyclonedx.parsers.JsonParser parser = new org.cyclonedx.parsers.JsonParser();
            Bom modifiedBom = parser.parse(fileToUpload.toFile());

            // 2. Assert that the Supplier metadata was successfully injected
            org.cyclonedx.model.Metadata metadata = modifiedBom.getMetadata();
            org.junit.jupiter.api.Assertions.assertEquals("Red Hat", metadata.getSupplier().getName());
            org.junit.jupiter.api.Assertions.assertEquals("https://www.redhat.com", metadata.getSupplier().getUrls().get(0));

            // 3. Assert that the Enhancer tool signature was injected
            boolean hasSBOMerTool = metadata.getToolChoice().getServices().stream()
                    .anyMatch(service -> "SBOMer".equals(service.getName()) && "1.0.0".equals(service.getVersion()));
            org.junit.jupiter.api.Assertions.assertTrue(hasSBOMerTool, "SBOMer service should be injected into the tool choice");

            return Map.of("bom.json", "http://permanent-url/bom.json");
        }).when(sbomStorage).uploadSbom(eq(generationId), eq(enhancementId), any(Path.class));

        // Act
        enhancerService.acceptRequest(enhancementId, generationId, correlationId, Map.of(), inputUrls);

        // Assert
        // Verify lifecycle events were published to Kafka
        verify(statusNotifier).notifyStatus(eq(enhancementId), eq(EnhancementStatus.ENHANCING), any(String.class), isNull());
        verify(statusNotifier).notifyStatus(eq(enhancementId), eq(EnhancementStatus.FINISHED), any(String.class), eq(List.of("http://permanent-url/bom.json")));

        // Verify the cleanup routine successfully deleted the temp file
        assertFalse(Files.exists(mockDownloadedFile), "Temp file should be deleted in the finally block");
    }

    @Test
    void testAcceptRequest_HandlesDownloadFailure() {
        // Arrange
        String enhancementId = "E-123";
        String generationId = "G-456";
        String correlationId = "CORR-999";
        List<String> inputUrls = List.of("/content/G-456/bad-bom.json");

        when(sbomStorage.downloadSbom("G-456/bad-bom.json"))
                .thenThrow(new RuntimeException("Storage unavailable"));

        // Act
        enhancerService.acceptRequest(enhancementId, generationId, correlationId, Map.of(), inputUrls);

        // Assert
        // Verify we entered the ENHANCING state...
        verify(statusNotifier).notifyStatus(eq(enhancementId), eq(EnhancementStatus.ENHANCING), any(String.class), isNull());

        // ...but transitioned to FAILED instead of FINISHED
        verify(statusNotifier).notifyStatus(eq(enhancementId), eq(EnhancementStatus.FAILED), eq("Enhancement failed: Storage unavailable"), isNull());

        // Verify that the global failure notifier was successfully triggered with the correct Correlation ID
        verify(failureNotifier).notify(any(), eq(correlationId), isNull());
    }
}