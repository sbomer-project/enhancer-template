package org.jboss.sbomer.enhancer.core.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cyclonedx.Version;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.OrganizationalEntity;
import org.cyclonedx.model.metadata.ToolInformation;
import org.cyclonedx.parsers.JsonParser;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.jboss.sbomer.enhancer.core.domain.EnhancementStatus;
import org.jboss.sbomer.enhancer.core.port.api.EnhancementOrchestrator;
import org.jboss.sbomer.enhancer.core.port.spi.FailureNotifier;
import org.jboss.sbomer.enhancer.core.port.spi.SBOMStorage;
import org.jboss.sbomer.enhancer.core.port.spi.StatusNotifier;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jboss.sbomer.enhancer.core.utility.FailureUtility;
import org.jboss.sbomer.events.common.FailureSpec;

// HELLO WORLD TYPE BUSINESS LOGIC FOR THE ENHANCER TEMPLATE - REPLACE WITH YOUR OWN LOGIC
@ApplicationScoped
@Slf4j
@RequiredArgsConstructor
public class EnhancerService implements EnhancementOrchestrator {

    private final SBOMStorage sbomStorage;
    private final StatusNotifier statusNotifier;
    private final FailureNotifier failureNotifier;

    @ConfigProperty(name = "sbomer.enhancer.tool.name", defaultValue = "SBOMer")
    String toolName;

    @ConfigProperty(name = "sbomer.enhancer.tool.version", defaultValue = "1.0.0")
    String toolVersion;

    @ConfigProperty(name = "sbomer.enhancer.supplier.name", defaultValue = "Red Hat")
    String supplierName;

    @ConfigProperty(name = "sbomer.enhancer.supplier.urls", defaultValue = "https://www.redhat.com")
    List<String> supplierUrls;

    @Override
    @WithSpan
    @Bulkhead(value = 10)
    public void acceptRequest(String enhancementId, String generationId, String correlationId, Map<String, String> enhancerOptions, List<String> inputSbomUrls) {

        Span span = Span.current();
        span.setAttribute("sbom.enhancementId", enhancementId);
        span.setAttribute("sbom.generationId", generationId);

        log.info("Starting enhancement processing for ID: {}, Generation ID: {}", enhancementId, generationId);
        List<String> finalUploadedUrls = new ArrayList<>();

        try {
            // 1. Send initial progress update to Kafka (enhancement.update)
            statusNotifier.notifyStatus(enhancementId, EnhancementStatus.ENHANCING, "Enhancement processing started", null);

            // 2. Process each target SBOM provided in the request payload
            for (String inputUrl : inputSbomUrls) {
                String relativeStoragePath = extractStoragePath(inputUrl);
                log.debug("Downloading target SBOM file from storage path: {}", relativeStoragePath);

                Path localSbomFile = null;
                try {
                    // Download via unified adapter
                    localSbomFile = sbomStorage.downloadSbom(relativeStoragePath);

                    // 3. Parse and append tool/supplier telemetry into the CycloneDX schema
                    enhanceCycloneDxMetadata(localSbomFile);

                    // 4. Upload the modified file back to the storage service endpoint
                    Map<String, String> uploadResult = sbomStorage.uploadSbom(generationId, enhancementId, localSbomFile);
                    finalUploadedUrls.addAll(uploadResult.values());

                } finally {
                    // Always clean up the temp storage file allocation to prevent container disk bloat
                    cleanupTempFile(localSbomFile);
                }
            }

            // 5. Fire the final success tracking state back to the orchestrator topic
            log.info("Successfully enhanced and uploaded {} SBOM assets for Enhancement ID: {}", finalUploadedUrls.size(), enhancementId);
            statusNotifier.notifyStatus(enhancementId, EnhancementStatus.FINISHED, "Enhancement completed successfully", finalUploadedUrls);

        } catch (Exception e) {
            log.error("Fatal exception encountered during enhancement processing for ID: {}", enhancementId, e);
            FailureSpec failureSpec = FailureUtility.buildFailureSpecFromException(e);
            failureNotifier.notify(failureSpec, correlationId, null);
            // 1. Dispatch standard status update failure (Updates DB state)
            statusNotifier.notifyStatus(enhancementId, EnhancementStatus.FAILED, "Enhancement failed: " + e.getMessage(), null);
            // We swallow the exception here so Kafka ACKs the original message and doesn't trap the service
        }
    }

    /**
     * Injects the defined tool choice info and supplier metrics directly into the
     * CycloneDX Metadata context of the target specification file.
     */
    private void enhanceCycloneDxMetadata(Path sbomPath) {
        try {
            log.debug("Parsing and appending tool definitions to: {}", sbomPath.toAbsolutePath());
            JsonParser parser = new JsonParser();
            Bom bom = parser.parse(sbomPath.toFile());

            Metadata metadata = bom.getMetadata();
            if (metadata == null) {
                metadata = new Metadata();
                bom.setMetadata(metadata);
            }

            // Append SBOMer tool info signature
            ToolInformation toolChoice = metadata.getToolChoice();
            if (toolChoice == null) {
                toolChoice = new ToolInformation();
                metadata.setToolChoice(toolChoice);
            }

            List<org.cyclonedx.model.Service> services = toolChoice.getServices();
            if (services == null) {
                services = new ArrayList<>();
                toolChoice.setServices(services);
            } else {
                services = new ArrayList<>(services);
            }

            org.cyclonedx.model.Service toolService = new org.cyclonedx.model.Service();
            toolService.setName(toolName);
            toolService.setVersion(toolVersion);
            services.add(toolService);
            toolChoice.setServices(services);

            // Establish Organization / Supplier metadata anchors
            OrganizationalEntity supplier = new OrganizationalEntity();
            supplier.setName(supplierName);
            supplier.setUrls(supplierUrls);
            metadata.setSupplier(supplier);

            // Serialize data changes using deterministic formats back onto disk
            BomJsonGenerator generator = new BomJsonGenerator(bom, Version.VERSION_16);
            Files.writeString(sbomPath, generator.toJsonString(), StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("Failed to inject tracking metadata signature into CycloneDX document", e);
        }
    }

    /**
     * Sanitizes incoming URLs by isolating the trailing generation ID and filename,
     * stripping out the scheme, host, and /api/v1/storage/content/ prefix.
     */
    private String extractStoragePath(String urlOrPath) {
        String pattern = "/content/";
        if (urlOrPath.contains(pattern)) {
            return urlOrPath.substring(urlOrPath.indexOf(pattern) + pattern.length());
        }
        return urlOrPath.startsWith("/") ? urlOrPath.substring(1) : urlOrPath;
    }

    private void cleanupTempFile(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                log.warn("Non-fatal clean up warning: Failed to purge sandbox temp asset: {}", tempFile, e);
            }
        }
    }
}