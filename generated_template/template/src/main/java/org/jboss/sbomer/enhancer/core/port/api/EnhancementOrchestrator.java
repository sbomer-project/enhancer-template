package org.jboss.sbomer.enhancer.core.port.api;

import java.util.List;
import java.util.Map;

/**
 * Driving Port (API) for the Enhancer Core Domain.
 * <p>
 * This defines the primary use cases supported by the enhancer service:
 * 1. Accepting new requests (Ingress).
 * 2. Handling updates from the execution environment (Feedback loop).
 * </p>
 */
public interface EnhancementOrchestrator {

    /**
     * Ingress Point: Accepts a new enhancement request.
     * <p>
     * Implementations should handle buffering, throttling, or immediate execution.
     * </p>
     *
     * @param enhancementId The unique ID of the enhancement.
     * @param generationId The unique ID of the generation the enhancement belongs to.
     * @param correlationId Correlation ID
     * @param enhancerOptions    The parameters passed for the enhancer to use.
     * @param inputSbomUrls      The list of URLs of the SBOMs to enhance.
     */
    void acceptRequest(String enhancementId, String generationId, String correlationId, Map<String, String> enhancerOptions, List<String> inputSbomUrls);

}
