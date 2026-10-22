package ru.itmo.highload_ml.tracking.service;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.exception.*;
import ru.itmo.highload_ml.tracking.mapper.MetricMapper;
import ru.itmo.highload_ml.tracking.model.*;
import ru.itmo.highload_ml.tracking.repository.*;

@Service
@RequiredArgsConstructor
public class MetricService {
    private final RunRepository runs;
    private final MetricRepository metrics;
    private final MetricMapper mapper;
    private final TransactionalOperator transactions;
    private final RunService runService;

    public Mono<MetricResponse> log(UUID id, CreateMetricRequest request) {
        return logBatch(id, new CreateMetricsRequest(List.of(request))).single();
    }

    public Flux<MetricResponse> logBatch(UUID id, CreateMetricsRequest request) {
        return transactions.execute(status -> runs.findById(id)
                .switchIfEmpty(Mono.error(new RunNotFoundException(id)))
                .flatMap(run -> {
                    if (run.getStatus() != RunStatus.RUNNING) return Mono.error(new RunNotRunningException(id, run.getStatus()));
                    return Flux.fromIterable(request.metrics()).concatMap(metric -> metrics.save(
                            new Metric(id, metric.name(), metric.value(), metric.step())))
                            .map(mapper::toResponse).collectList();
                })).single()
                .flatMapMany(Flux::fromIterable);
    }
    public Mono<MetricResponse> getById(UUID runId, UUID metricId) {
        return runService.requireRun(runId).then(metrics.findByIdAndRunId(metricId, runId))
                .switchIfEmpty(Mono.error(new MetricNotFoundException(metricId))).map(mapper::toResponse);
    }
    public Mono<Long> count(UUID runId) { return runService.requireRun(runId).then(metrics.countByRunId(runId)); }
    public Flux<MetricResponse> findAll(UUID runId, Pageable pageable) {
        return runService.requireRun(runId).thenMany(metrics.findByRunId(runId, pageable)).map(mapper::toResponse);
    }
}
