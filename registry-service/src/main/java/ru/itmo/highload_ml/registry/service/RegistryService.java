package ru.itmo.highload_ml.registry.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import ru.itmo.highload_ml.registry.api.dto.ModelVersionResponse;
import ru.itmo.highload_ml.registry.api.dto.RegisterModelVersionRequest;
import ru.itmo.highload_ml.registry.client.ProjectClient;
import ru.itmo.highload_ml.registry.client.TrackingClient;
import ru.itmo.highload_ml.registry.exception.InvalidModelArtifactException;

@Service
@RequiredArgsConstructor
public class RegistryService {
    private final ProjectClient projects;
    private final TrackingClient tracking;
    private final ModelVersionService versions;

    public ModelVersionResponse register(UUID projectId, RegisterModelVersionRequest request) {
        projects.membership(projectId, request.userId());
        var artifact = tracking.artifact(request.artifactId());
        if (!"MODEL".equals(artifact.type())) {
            throw new InvalidModelArtifactException(request.artifactId(), "artifact type must be MODEL");
        }
        if (!"COMPLETED".equals(artifact.runStatus())) {
            throw new InvalidModelArtifactException(request.artifactId(), "source run must be COMPLETED");
        }

        if (!projectId.equals(projects.experiment(artifact.experimentId()).projectId())) {
            throw new InvalidModelArtifactException(request.artifactId(), "artifact belongs to another project");
        }
        return versions.register(projectId, request.artifactId());
    }

    public ModelVersionResponse getById(UUID projectId, UUID id) {
        projects.project(projectId);
        return versions.getById(projectId, id);
    }

    public Page<ModelVersionResponse> findAll(UUID projectId, Pageable pageable) {
        projects.project(projectId);
        return versions.findAll(projectId, pageable);
    }

    public ModelVersionResponse getProduction(UUID projectId) {
        projects.project(projectId);
        return versions.getProduction(projectId);
    }

    public ModelVersionResponse stage(UUID projectId, UUID id, UUID userId) {
        projects.membership(projectId, userId);
        return versions.stage(projectId, id);
    }

    public ModelVersionResponse promote(UUID projectId, UUID id, UUID userId) {
        projects.membership(projectId, userId);
        return versions.promote(projectId, id);
    }
}
