package org.jboss.sbomer.enhancer.core.port.spi;

import java.nio.file.Path;
import java.util.Map;

public interface SBOMStorage {
    /**
     * Downloads the SBOM and saves it to a temporary file.
     *
     * @param path The URL path of the SBOM on the storage service.
     * @return The Path to the local temporary file.
     */
    Path downloadSbom(String path);

    /**
     * Uploads a locally modified SBOM file back to the storage service.
     *
     * @param generationId The Generation ID the SBOM belongs to.
     * @param enhancementId The Enhancement ID representing this specific step.
     * @param sbomFilePath The local path to the file to be uploaded.
     * @return A map of Filename -> Permanent URL returned by the storage service.
     */
    Map<String, String> uploadSbom(String generationId, String enhancementId, Path sbomFilePath);
}