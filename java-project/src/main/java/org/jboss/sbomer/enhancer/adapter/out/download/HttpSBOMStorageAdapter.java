package org.jboss.sbomer.enhancer.adapter.out.download;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.sbomer.enhancer.adapter.out.download.exception.SBOMStorageException;
import org.jboss.sbomer.enhancer.core.port.spi.SBOMStorage;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class HttpSBOMStorageAdapter implements SBOMStorage {

    private final ManifestStorageApiClient storageClient;

    public HttpSBOMStorageAdapter(@RestClient ManifestStorageApiClient storageClient) {
        this.storageClient = storageClient;
    }

    @Override
    @WithSpan
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    public Path downloadSbom(@SpanAttribute("sbom.path") String path) {
        log.debug("Downloading SBOM from path: {}", path);
        Path tempFile = null;

        try {
            tempFile = Files.createTempFile("sbomer-download-", ".json");
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;

            try (InputStream is = storageClient.download(cleanPath)) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("Successfully downloaded SBOM to temp file: {}", tempFile.toAbsolutePath());
            return tempFile;

        } catch (Exception e) {
            cleanupTempFile(tempFile);
            recordErrorOnSpan(e);

            String errorCode = extractErrorCode(e);
            log.error("Storage Service rejected download request. Status: {}, Path: {}", errorCode, path);

            if (e instanceof SBOMStorageException) {
                throw (SBOMStorageException) e;
            }
            throw new SBOMStorageException(errorCode, "Error downloading SBOM: " + e.getMessage(), e);
        }
    }

    @Override
    @WithSpan
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    public Map<String, String> uploadSbom(
            @SpanAttribute("generation.id") String generationId,
            @SpanAttribute("enhancement.id") String enhancementId,
            Path sbomFilePath) {

        log.debug("Uploading SBOM for generation: {}, enhancement: {} from path: {}", generationId, enhancementId, sbomFilePath);

        try {
            // The RestClient handles the multipart/form-data conversion automatically
            Map<String, String> result = storageClient.uploadEnhancement(generationId, enhancementId, sbomFilePath.toFile());

            log.debug("Successfully uploaded SBOM. Result URLs: {}", result);
            return result;

        } catch (Exception e) {
            recordErrorOnSpan(e);

            String errorCode = extractErrorCode(e);
            log.error("Storage Service rejected upload request. Status: {}, Generation: {}", errorCode, generationId);

            if (e instanceof SBOMStorageException) {
                throw (SBOMStorageException) e;
            }
            throw new SBOMStorageException(errorCode, "Error uploading SBOM: " + e.getMessage(), e);
        }
    }

    private void cleanupTempFile(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception cleanupEx) {
                log.warn("Failed to clean up temp file after download failure: {}", tempFile, cleanupEx);
            }
        }
    }

    private void recordErrorOnSpan(Exception e) {
        Span span = Span.current();
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getMessage());
    }

    private String extractErrorCode(Exception e) {
        if (e instanceof WebApplicationException wae) {
            return String.valueOf(wae.getResponse().getStatus());
        }
        return null;
    }
}