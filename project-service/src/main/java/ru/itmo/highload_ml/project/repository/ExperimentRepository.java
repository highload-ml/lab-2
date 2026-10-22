package ru.itmo.highload_ml.project.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload_ml.project.model.Experiment;

import java.util.Optional;
import java.util.UUID;

public interface ExperimentRepository extends JpaRepository<Experiment, UUID> {

    boolean existsByProject_Id(UUID projectId);

    boolean existsByIdAndActiveTrue(UUID id);

    Optional<Experiment> findByIdAndActiveTrue(UUID id);

    boolean existsByProject_IdAndName(UUID projectId, String name);

    Page<Experiment> findByProject_IdAndActiveTrue(UUID projectId, Pageable pageable);

    Optional<Experiment> findByIdAndProject_IdAndActiveTrue(UUID id, UUID projectId);

    @EntityGraph(attributePaths = "tags")
    Optional<Experiment> findWithTagsByIdAndProject_IdAndActiveTrue(UUID id, UUID projectId);

    Page<Experiment> findByProject_IdAndActiveTrueAndTags_Id(UUID projectId, UUID tagId, Pageable pageable);
}
