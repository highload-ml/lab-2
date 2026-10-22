package ru.itmo.highload_ml.registry.repository;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import ru.itmo.highload_ml.registry.model.ModelVersion;
import ru.itmo.highload_ml.registry.model.ModelVersionState;

@Repository
public interface ModelVersionRepository extends JpaRepository<ModelVersion, UUID> {
    Optional<ModelVersion> findByIdAndProjectId(UUID id, UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ModelVersion> findLockedByIdAndProjectId(UUID id, UUID projectId);

    Optional<ModelVersion> findByProjectIdAndState(UUID projectId, ModelVersionState state);

    boolean existsByArtifactId(UUID artifactId);

    Page<ModelVersion> findByProjectId(UUID projectId, Pageable pageable);
}
