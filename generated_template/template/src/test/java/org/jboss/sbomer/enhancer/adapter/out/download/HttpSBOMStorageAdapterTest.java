package org.jboss.sbomer.enhancer.adapter.out.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jboss.sbomer.enhancer.adapter.out.download.exception.SBOMStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@ExtendWith(MockitoExtension.class)
class HttpSBOMStorageAdapterTest {

    @Mock
    ManifestStorageApiClient storageClient;

    @InjectMocks
    HttpSBOMStorageAdapter adapter;

    @TempDir
    Path tempDir;

    @Test
    void testDownloadSbom_Success() throws Exception {
        // Arrange
        String targetPath = "G-456/bom.json";
        String dummyContent = "{\"fake\":\"sbom\"}";
        InputStream mockStream = new ByteArrayInputStream(dummyContent.getBytes(StandardCharsets.UTF_8));
        
        when(storageClient.download(targetPath)).thenReturn(mockStream);

        // Act
        Path downloadedFile = adapter.downloadSbom(targetPath);

        // Assert
        assertNotNull(downloadedFile);
        assertTrue(Files.exists(downloadedFile));
        assertEquals(dummyContent, Files.readString(downloadedFile));

        // Clean up the generated temp file
        Files.deleteIfExists(downloadedFile);
    }

    @Test
    void testDownloadSbom_StripsLeadingSlash() throws Exception {
        // Arrange
        String dirtyPath = "/G-456/bom.json";
        String cleanPath = "G-456/bom.json";
        InputStream mockStream = new ByteArrayInputStream("{}".getBytes());
        
        // Mock to expect the clean path
        when(storageClient.download(cleanPath)).thenReturn(mockStream);

        // Act
        Path downloadedFile = adapter.downloadSbom(dirtyPath);

        // Assert
        verify(storageClient).download(cleanPath);
        Files.deleteIfExists(downloadedFile);
    }

    @Test
    void testDownloadSbom_HandlesWebApplicationException() {
        // Arrange
        String targetPath = "G-456/bom.json";
        
        // Simulate a 404 Not Found from the storage service
        WebApplicationException notFoundEx = new NotFoundException("Not Found", Response.status(404).build());
        when(storageClient.download(targetPath)).thenThrow(notFoundEx);

        // Act & Assert
        SBOMStorageException thrown = assertThrows(SBOMStorageException.class, () -> {
            adapter.downloadSbom(targetPath);
        });

        // Verify the status code was successfully extracted and wrapped
        assertEquals("404", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("Not Found"));
    }

    @Test
    void testUploadSbom_Success() throws Exception {
        // Arrange
        String generationId = "G-456";
        String enhancementId = "E-123";
        Path dummyFile = tempDir.resolve("bom.json");
        Files.writeString(dummyFile, "{}");

        Map<String, String> expectedResult = Map.of("bom.json", "http://storage/content/G-456/E-123/bom.json");

        when(storageClient.uploadEnhancement(eq(generationId), eq(enhancementId), any()))
                .thenReturn(expectedResult);

        // Act
        Map<String, String> result = adapter.uploadSbom(generationId, enhancementId, dummyFile);

        // Assert
        assertEquals(expectedResult, result);
        verify(storageClient).uploadEnhancement(eq(generationId), eq(enhancementId), any());
    }

    @Test
    void testUploadSbom_HandlesGenericException() throws Exception {
        // Arrange
        String generationId = "G-456";
        String enhancementId = "E-123";
        Path dummyFile = tempDir.resolve("bom.json");
        Files.writeString(dummyFile, "{}");

        // Simulate a connection timeout or general runtime exception (no HTTP status code)
        when(storageClient.uploadEnhancement(eq(generationId), eq(enhancementId), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        // Act & Assert
        SBOMStorageException thrown = assertThrows(SBOMStorageException.class, () -> {
            adapter.uploadSbom(generationId, enhancementId, dummyFile);
        });

        // Verify it mapped the generic exception properly without a status code
        assertNull(thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("Connection refused"));
    }
}