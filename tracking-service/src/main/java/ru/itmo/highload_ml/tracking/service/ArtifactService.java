package ru.itmo.highload_ml.tracking.service;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.exception.*;
import ru.itmo.highload_ml.tracking.mapper.ArtifactMapper;
import ru.itmo.highload_ml.tracking.model.*;
import ru.itmo.highload_ml.tracking.repository.*;

@Service
@RequiredArgsConstructor
public class ArtifactService {
    private final RunRepository runs;
    private final ArtifactRepository artifacts;
    private final ArtifactMapper mapper;
    private final TransactionalOperator transactions;
    private final RunService runService;

    /** Validate status and insert metadata in one transaction. */
    public Mono<ArtifactResponse> register(UUID id, CreateArtifactRequest request) {
        return transactions.execute(status -> runs.findById(id)
                .switchIfEmpty(Mono.error(new RunNotFoundException(id)))
                .flatMap(run -> run.getStatus() == RunStatus.RUNNING
                        ? artifacts.save(new Artifact(id, request.name(), request.type(), request.path(), request.sizeBytes()))
                        : Mono.error(new RunNotAcceptingArtifactsException(id, run.getStatus())))
                .map(mapper::toResponse)).single()
                .onErrorMap(error -> SqlErrors.unique(error, "uk_artifacts_run_name"),
                        error -> new ArtifactNameAlreadyTakenException(id, request.name()));
    }
    public Mono<ArtifactResponse> getById(UUID runId, UUID artifactId) {
        return runService.requireRun(runId).then(artifacts.findByIdAndRunId(artifactId, runId))
                .switchIfEmpty(Mono.error(new ArtifactNotFoundException(artifactId))).map(mapper::toResponse);
    }
    public Mono<Long> count(UUID runId) { return runService.requireRun(runId).then(artifacts.countByRunId(runId)); }
    public Flux<ArtifactResponse> findAll(UUID runId, Pageable pageable) {
        return runService.requireRun(runId).thenMany(artifacts.findByRunId(runId, pageable)).map(mapper::toResponse);
    }
    public Mono<ArtifactDetails> details(UUID id) {
        return artifacts.findById(id).switchIfEmpty(Mono.error(new ArtifactNotFoundException(id)))
                .flatMap(artifact -> runService.requireRun(artifact.getRunId()).map(run ->
                        new ArtifactDetails(id, run.getExperimentId(), artifact.getType(), run.getStatus())));
    }
    public record ArtifactDetails(UUID id, UUID experimentId, ArtifactType type, RunStatus runStatus) { }
}
