package org.jboss.sbomer.enhancer.adapter.out.download;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestForm;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@RegisterRestClient(configKey = "manifest-storage")
@Path("/api/v1/storage")
public interface ManifestStorageApiClient {

    @GET
    @Path("/content/{path:.+}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    InputStream download(@PathParam("path") @Encoded String path);

    @POST
    @Path("/generations/{generationId}/enhancements/{enhancementId}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, String> uploadEnhancement(
            @PathParam("generationId") String generationId,
            @PathParam("enhancementId") String enhancementId,
            @RestForm("files") File file);
}