package ru.itmo.highload_ml.tracking.client;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
@FeignClient(name = "project-service", url = "${app.project-service.url:}")
public interface ProjectClient {
    @GetMapping("/internal/experiments/{id}")
    ExperimentDetails experiment(@PathVariable("id") UUID id);
    @GetMapping("/internal/projects/{projectId}/members/{userId}")
    MembershipDetails membership(@PathVariable("projectId") UUID projectId, @PathVariable("userId") UUID userId);
    record ExperimentDetails(UUID id, UUID projectId, boolean active) { }
    record MembershipDetails(UUID projectId, UUID userId, String role) { }
}
