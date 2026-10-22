package ru.itmo.highload_ml.registry.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload_ml.registry.api.dto.ModelVersionResponse;
import ru.itmo.highload_ml.registry.exception.*;
import ru.itmo.highload_ml.registry.model.ModelVersion;
import ru.itmo.highload_ml.registry.model.ModelVersionState;
import ru.itmo.highload_ml.registry.repository.ModelVersionRepository;
import ru.itmo.highload_ml.registry.repository.RegistryCounterRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ModelVersionService {
    private final ModelVersionRepository versions;
    private final RegistryCounterRepository counter;

    @Transactional
    public ModelVersionResponse register(UUID projectId, UUID artifactId) {
        if (versions.existsByArtifactId(artifactId)) {
            throw new ModelArtifactAlreadyRegisteredException(artifactId);
        }
        long number = counter.allocate(projectId);
        return response(versions.saveAndFlush(new ModelVersion(projectId, artifactId, number)));
    }

    public ModelVersionResponse getById(UUID projectId, UUID id) {
        return response(versions.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new ModelVersionNotFoundException(id)));
    }

    public Page<ModelVersionResponse> findAll(UUID projectId, Pageable pageable) {
        return versions.findByProjectId(projectId, pageable).map(this::response);
    }

    public ModelVersionResponse getProduction(UUID projectId) {
        return response(versions.findByProjectIdAndState(projectId, ModelVersionState.PRODUCTION)
                .orElseThrow(() -> new ModelVersionNotFoundException(projectId, "production")));
    }

    @Transactional
    public ModelVersionResponse stage(UUID projectId, UUID id) {
        ModelVersion version = locked(projectId, id);
        version.stage();
        versions.flush();
        return response(version);
    }

    @Transactional
    public ModelVersionResponse promote(UUID projectId, UUID id) {
        counter.lock(projectId);
        ModelVersion target = locked(projectId, id);
        if (target.getState() != ModelVersionState.STAGING) {
            throw new InvalidModelVersionTransitionException(id, target.getState(), ModelVersionState.PRODUCTION);
        }
        versions.findByProjectIdAndState(projectId, ModelVersionState.PRODUCTION).ifPresent(current -> {
            current.archive();

            versions.flush();
        });
        target.promote(Instant.now().truncatedTo(ChronoUnit.MICROS));
        versions.flush();
        return response(target);
    }

    private ModelVersion locked(UUID projectId, UUID id) {
        return versions.findLockedByIdAndProjectId(id, projectId).orElseThrow(() -> new ModelVersionNotFoundException(id));
    }

    private ModelVersionResponse response(ModelVersion version) {
        return new ModelVersionResponse(version.getId(), version.getProjectId(), version.getArtifactId(),
                version.getVersion(), version.getState(), version.getCreatedAt(), version.getPromotedAt());
    }
}
