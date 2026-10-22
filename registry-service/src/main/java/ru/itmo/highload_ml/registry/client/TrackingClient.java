package ru.itmo.highload_ml.registry.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "tracking-service", url = "${app.tracking-service.url:}")
public interface TrackingClient {
    @GetMapping("/internal/artifacts/{id}")
    ArtifactDetails artifact(@PathVariable("id") UUID id);

    record ArtifactDetails(UUID id, UUID experimentId, String type, String runStatus) { }
}
