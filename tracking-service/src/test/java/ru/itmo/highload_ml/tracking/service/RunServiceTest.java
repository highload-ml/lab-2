package ru.itmo.highload_ml.tracking.service;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import ru.itmo.highload_ml.tracking.repository.RunRepository;
import ru.itmo.highload_ml.tracking.mapper.RunMapper;
import ru.itmo.highload_ml.tracking.port.ProjectAccess;
import ru.itmo.highload_ml.tracking.model.*;
import ru.itmo.highload_ml.tracking.api.dto.CreateRunRequest;
import ru.itmo.highload_ml.tracking.exception.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RunServiceTest extends ServiceTestSupport {
    @Mock RunRepository runs;
    @Mock ProjectAccess projects;
    RunService service;
    UUID id = UUID.randomUUID();
    UUID experimentId = UUID.randomUUID();
    @BeforeEach void setup() { service = new RunService(runs, new RunMapper(), projects, transactions); }
    static Stream<Arguments> transitions() {
        return Stream.of(RunStatus.values()).flatMap(from -> Stream.of(RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.FAILED)
                .map(to -> Arguments.of(from, to)));
    }
    @ParameterizedTest @MethodSource("transitions")
    void permitsOnlyValidTransitions(RunStatus from, RunStatus to) {
        Run run = new Run(experimentId, UUID.randomUUID(), "run");
        run.setId(id); run.setStatus(from); run.setStartedAt(Instant.now().plusSeconds(60));
        when(runs.findById(id)).thenReturn(Mono.just(run));
        boolean valid = (from == RunStatus.CREATED && to == RunStatus.RUNNING)
                || (from == RunStatus.RUNNING && to != RunStatus.RUNNING);
        if (valid) when(runs.save(run)).thenReturn(Mono.just(run));
        Mono<?> operation = to == RunStatus.RUNNING ? service.start(id) : to == RunStatus.COMPLETED ? service.complete(id) : service.fail(id);
        if (valid) {
            StepVerifier.create(operation).expectNextCount(1).verifyComplete();
            assertThat(run.getStatus()).isEqualTo(to);
            if (to != RunStatus.RUNNING) assertThat(run.getFinishedAt()).isAfterOrEqualTo(run.getStartedAt());
        } else StepVerifier.create(operation).expectError(InvalidRunStateTransitionException.class).verify();
    }
    @Test void rejectsUnknownRun() {
        when(runs.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(service.getById(id)).expectError(RunNotFoundException.class).verify();
    }
    @Test void rejectsUnknownRunOnTransition() {
        when(runs.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(service.start(id)).expectError(RunNotFoundException.class).verify();
    }
    @Test void remoteFailureDoesNotWrite() {
        UUID author = UUID.randomUUID();
        when(projects.requireAuthor(experimentId, author)).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        StepVerifier.create(service.create(experimentId, new CreateRunRequest(author, "run"))).expectError().verify();
        verifyNoInteractions(runs);
    }
    @Test void createsOnlyAfterMembershipCheck() {
        UUID author = UUID.randomUUID();
        when(projects.requireAuthor(experimentId, author)).thenReturn(Mono.empty());
        when(runs.save(any())).thenAnswer(call -> { Run run = call.getArgument(0); run.setId(id); return Mono.just(run); });
        StepVerifier.create(service.create(experimentId, new CreateRunRequest(author, "run")))
                .assertNext(run -> { assertThat(run.id()).isEqualTo(id); assertThat(run.status()).isEqualTo(RunStatus.CREATED); }).verifyComplete();
    }
    @Test void scrollingReturnsPageAndOneLookaheadRow() {
        var page = PageRequest.of(1, 2, Sort.by("createdAt", "id").descending());
        Run run = new Run(experimentId, UUID.randomUUID(), "run"); run.setId(id);
        when(projects.requireActiveExperiment(experimentId)).thenReturn(Mono.empty());
        when(runs.findByExperimentId(experimentId, page)).thenReturn(Flux.just(run, run));
        when(runs.findByExperimentId(experimentId, page.next())).thenReturn(Flux.just(run, run));
        StepVerifier.create(service.findAll(experimentId, page)).expectNextCount(3).verifyComplete();
    }
    @Test void emptyPageReturnsNoRows() {
        var page = PageRequest.of(0, 3, Sort.by("createdAt", "id").descending());
        when(projects.requireActiveExperiment(experimentId)).thenReturn(Mono.empty());
        when(runs.findByExperimentId(experimentId, page)).thenReturn(Flux.empty());
        when(runs.findByExperimentId(experimentId, page.next())).thenReturn(Flux.empty());
        StepVerifier.create(service.findAll(experimentId, page)).verifyComplete();
    }
}
