package ru.itmo.highload_ml.tracking.service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.exception.*;
import ru.itmo.highload_ml.tracking.mapper.RunMapper;
import ru.itmo.highload_ml.tracking.model.*;
import ru.itmo.highload_ml.tracking.port.ProjectAccess;
import ru.itmo.highload_ml.tracking.repository.RunRepository;

@Service
@RequiredArgsConstructor
public class RunService {
    private final RunRepository runs;
    private final RunMapper mapper;
    private final ProjectAccess projects;
    private final TransactionalOperator transactions;

    public Mono<RunResponse> create(UUID experimentId, CreateRunRequest request) {
        return projects.requireAuthor(experimentId, request.authorId())
                .then(Mono.defer(() -> runs.save(new Run(experimentId, request.authorId(), request.name()))))
                .map(mapper::toResponse);
    }
    public Mono<RunResponse> getById(UUID id) { return requireRun(id).map(mapper::toResponse); }
    public Mono<Run> requireRun(UUID id) {
        return runs.findById(id).switchIfEmpty(Mono.error(new RunNotFoundException(id)));
    }
    public Flux<RunResponse> findAll(UUID experimentId, Pageable page) {
        return projects.requireActiveExperiment(experimentId).thenMany(Flux.defer(() ->
                runs.findByExperimentId(experimentId, page)
                        .concatWith(Flux.defer(() -> runs.findByExperimentId(experimentId, page.next()).take(1)))))
                .map(mapper::toResponse);
    }

    public Mono<RunResponse> start(UUID id) { return transition(id, RunStatus.RUNNING); }
    public Mono<RunResponse> complete(UUID id) { return transition(id, RunStatus.COMPLETED); }
    public Mono<RunResponse> fail(UUID id) { return transition(id, RunStatus.FAILED); }

    private Mono<RunResponse> transition(UUID id, RunStatus target) {
        return transactions.execute(status -> runs.findById(id)
                .switchIfEmpty(Mono.error(new RunNotFoundException(id)))
                .flatMap(run -> {
                    RunStatus expected = target == RunStatus.RUNNING ? RunStatus.CREATED : RunStatus.RUNNING;
                    if (run.getStatus() != expected) return Mono.error(new InvalidRunStateTransitionException(id, run.getStatus(), target));
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    if (target == RunStatus.RUNNING) run.setStartedAt(now);
                    else run.setFinishedAt(now.isBefore(run.getStartedAt()) ? run.getStartedAt() : now);
                    run.setStatus(target);
                    return runs.save(run).map(mapper::toResponse);
                })).single();
    }
}
