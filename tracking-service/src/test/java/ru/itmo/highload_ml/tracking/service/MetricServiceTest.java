package ru.itmo.highload_ml.tracking.service;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import ru.itmo.highload_ml.tracking.repository.*;
import ru.itmo.highload_ml.tracking.mapper.MetricMapper;
import ru.itmo.highload_ml.tracking.model.*;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.exception.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class MetricServiceTest extends ServiceTestSupport {
    @Mock RunRepository runs;
    @Mock MetricRepository metrics;
    @Mock RunService runService;
    MetricService service;
    UUID id = UUID.randomUUID();
    CreateMetricRequest request = new CreateMetricRequest("loss", BigDecimal.ONE, 0L);
    @BeforeEach void setup() { service = new MetricService(runs, metrics, new MetricMapper(), transactions, runService); }
    Run run(RunStatus status) { Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "run"); run.setId(id); run.setStatus(status); return run; }
    @Test void rejectsNonRunningRun() {
        when(runs.findById(id)).thenReturn(Mono.just(run(RunStatus.COMPLETED)));
        StepVerifier.create(service.log(id, request)).expectError(RunNotRunningException.class).verify();
        verifyNoInteractions(metrics);
    }
    @Test void rejectsUnknownRun() {
        when(runs.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(service.log(id, request)).expectError(RunNotFoundException.class).verify();
    }
    @Test void returnsSavedBatch() {
        when(runs.findById(id)).thenReturn(Mono.just(run(RunStatus.RUNNING)));
        when(metrics.save(any())).thenAnswer(call -> { Metric metric = call.getArgument(0); metric.setId(UUID.randomUUID()); return Mono.just(metric); });
        StepVerifier.create(service.logBatch(id, new CreateMetricsRequest(List.of(request, new CreateMetricRequest("loss", BigDecimal.TEN, 1L)))))
                .assertNext(metric -> assertThat(metric.step()).isZero()).assertNext(metric -> assertThat(metric.step()).isEqualTo(1)).verifyComplete();
    }
    @Test void doesNotEmitPartialBatchOnInsertFailure() {
        when(runs.findById(id)).thenReturn(Mono.just(run(RunStatus.RUNNING)));
        when(metrics.save(any())).thenAnswer(call -> {
            Metric metric = call.getArgument(0);
            return metric.getStep() == 0 ? Mono.just(metric) : Mono.error(new IllegalStateException("DB failure"));
        });
        StepVerifier.create(service.logBatch(id, new CreateMetricsRequest(List.of(request, new CreateMetricRequest("loss", BigDecimal.TEN, 1L)))))
                .expectErrorMessage("DB failure").verify();
    }
    @Test void readsOnlyWithinRequestedRun() {
        when(runService.requireRun(id)).thenReturn(Mono.just(run(RunStatus.RUNNING)));
        UUID metricId = UUID.randomUUID();
        when(metrics.findByIdAndRunId(metricId, id)).thenReturn(Mono.empty());
        StepVerifier.create(service.getById(id, metricId)).expectError(MetricNotFoundException.class).verify();
    }
    @Test void listsAndCounts() {
        var page = PageRequest.of(1, 2, Sort.by("recordedAt", "id"));
        when(runService.requireRun(id)).thenReturn(Mono.just(run(RunStatus.RUNNING)));
        when(metrics.countByRunId(id)).thenReturn(Mono.just(4L));
        when(metrics.findByRunId(id, page)).thenReturn(Flux.just(new Metric(id, "loss", BigDecimal.ONE, 2)));
        StepVerifier.create(service.count(id)).expectNext(4L).verifyComplete();
        StepVerifier.create(service.findAll(id, page)).expectNextCount(1).verifyComplete();
    }
}
