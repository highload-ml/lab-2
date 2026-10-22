package ru.itmo.highload_ml.registry.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "project-service", url = "${app.project-service.url:}")
public interface ProjectClient {
    @GetMapping("/internal/projects/{id}")
    ProjectDetails project(@PathVariable("id") UUID id);

    @GetMapping("/internal/projects/{projectId}/members/{userId}")
    MembershipDetails membership(@PathVariable("projectId") UUID projectId, @PathVariable("userId") UUID userId);

    @GetMapping("/internal/experiments/{id}")
    ExperimentDetails experiment(@PathVariable("id") UUID id);

    record ProjectDetails(UUID id) { }
    record MembershipDetails(UUID projectId, UUID userId, String role) { }
    record ExperimentDetails(UUID id, UUID projectId, boolean active) { }
}
