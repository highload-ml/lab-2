package ru.itmo.highload_ml.project.api.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.highload_ml.project.service.InternalProjectService;
import ru.itmo.highload_ml.project.model.ProjectRole;

import java.util.UUID;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalProjectController {
    private final InternalProjectService service;

    @GetMapping("/projects/{projectId}")
    public ProjectDetails project(@PathVariable UUID projectId) {
        return service.project(projectId);
    }

    @GetMapping("/experiments/{experimentId}")
    public ExperimentDetails experiment(@PathVariable UUID experimentId) {
        return service.experiment(experimentId);
    }

    @GetMapping("/projects/{projectId}/members/{userId}")
    public MembershipDetails membership(@PathVariable UUID projectId, @PathVariable UUID userId) {
        return service.membership(projectId, userId);
    }

    public record ProjectDetails(UUID id) { }
    public record ExperimentDetails(UUID id, UUID projectId, boolean active) { }
    public record MembershipDetails(UUID projectId, UUID userId, ProjectRole role) { }
}
