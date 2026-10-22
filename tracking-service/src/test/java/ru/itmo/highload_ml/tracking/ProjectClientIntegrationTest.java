package ru.itmo.highload_ml.tracking;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import ru.itmo.highload_ml.tracking.api.dto.CreateRunRequest;
import ru.itmo.highload_ml.tracking.model.RunStatus;
import ru.itmo.highload_ml.tracking.port.ProjectAccess;
import ru.itmo.highload_ml.tracking.repository.RunRepository;
import ru.itmo.highload_ml.tracking.service.RunService;
import static org.assertj.core.api.Assertions.*;

/** Real Feign proxy, circuit breaker, HTTP API and PostgreSQL. Only the remote project is simulated. */
class ProjectClientIntegrationTest extends BaseIntegrationTest {
    static final UUID EXPERIMENT = UUID.randomUUID(), INACTIVE = UUID.randomUUID();
    static final UUID PROJECT = UUID.randomUUID(), AUTHOR = UUID.randomUUID();
    static final AtomicInteger STATUS = new AtomicInteger(200), MEMBERSHIP_STATUS = new AtomicInteger(200);
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicBoolean SLOW = new AtomicBoolean(), DISCONNECT = new AtomicBoolean();
    static final DisposableServer SERVER = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                CALLS.incrementAndGet();
                if (DISCONNECT.get()) {
                    response.withConnection(connection -> connection.dispose());
                    return Mono.empty();
                }
                response.header("Content-Type", "application/json");
                boolean membership = request.uri().contains("/members/");
                int status = membership ? MEMBERSHIP_STATUS.get() : STATUS.get();
                if (status != 200) return response.status(status).sendString(Mono.just("SECRET upstream diagnostics"));
                boolean active = !request.uri().endsWith(INACTIVE.toString());
                String body = membership
                        ? "{\"projectId\":\"" + PROJECT + "\",\"userId\":\"" + AUTHOR + "\",\"role\":\"EDITOR\"}"
                        : "{\"id\":\"" + (active ? EXPERIMENT : INACTIVE) + "\",\"projectId\":\"" + PROJECT + "\",\"active\":" + active + "}";
                return response.sendString(SLOW.get() ? Mono.delay(Duration.ofSeconds(1)).map(ignored -> body) : Mono.just(body));
            }).bindNow();

    @DynamicPropertySource static void projectUrl(DynamicPropertyRegistry properties) {
        properties.add("app.project-service.url", () -> "http://127.0.0.1:" + SERVER.port());
        properties.add("spring.cloud.openfeign.client.config.project-service.connectTimeout", () -> 100);
        properties.add("spring.cloud.openfeign.client.config.project-service.readTimeout", () -> 200);
    }
    @Autowired RunService runs;
    @Autowired RunRepository repository;
    @Autowired ProjectAccess projects;
    @Autowired CircuitBreakerRegistry breakers;
    @LocalServerPort int port;
    WebTestClient http;

    @BeforeEach void resetDependency() {
        STATUS.set(200); MEMBERSHIP_STATUS.set(200); CALLS.set(0); SLOW.set(false); DISCONNECT.set(false);
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll static void stop() { SERVER.disposeNow(); }

    @Test void realFeignProxyDecodesProjectDtosAndAllowsCreation() {
        var created = runs.create(EXPERIMENT, new CreateRunRequest(AUTHOR, "real-feign")).block(Duration.ofSeconds(5));
        assertThat(created.id()).isNotNull();
        assertThat(created.experimentId()).isEqualTo(EXPERIMENT);
        assertThat(created.status()).isEqualTo(RunStatus.CREATED);
    }
    @Test void realFeignProxyRejectsSoftDeletedExperiment() {
        StepVerifier.create(projects.requireAuthor(INACTIVE, AUTHOR)).expectErrorSatisfies(error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404)).verify(Duration.ofSeconds(5));
    }
    @Test void opensCircuitSkipsNetworkAndRecoversWithoutWritingOnFailure() {
        long before = repository.count().block();
        STATUS.set(500);
        for (int i = 0; i < 5; i++) create(503);
        var circuit = breakers.circuitBreaker("project-service");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(CALLS.get()).isEqualTo(5); // Feign does not retry the failed calls.
        create(503);
        assertThat(CALLS.get()).isEqualTo(5);
        assertThat(repository.count().block()).isEqualTo(before);
        STATUS.set(200);
        circuit.transitionToHalfOpenState(); // Deterministic recovery probe; no wall-clock sleep.
        create(201); // Experiment + membership are the two permitted successful probes.
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(repository.count().block()).isEqualTo(before + 1);
    }
    @ParameterizedTest @ValueSource(ints = {404, 422})
    void businessErrorsKeepStatusAndDoNotOpenCircuit(int status) {
        STATUS.set(status);
        for (int i = 0; i < 8; i++) create(status);
        var circuit = breakers.circuitBreaker("project-service");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
    }
    @Test void membershipErrorDoesNotAuthorizeOrInsert() {
        long before = repository.count().block();
        MEMBERSHIP_STATUS.set(422);
        create(422);
        assertThat(repository.count().block()).isEqualTo(before);
        assertThat(breakers.circuitBreaker("project-service").getMetrics().getNumberOfFailedCalls()).isZero();
    }
    @Test void readTimeoutReturns503AndDoesNotInsert() {
        long before = repository.count().block();
        SLOW.set(true);
        create(503);
        assertThat(repository.count().block()).isEqualTo(before);
        assertThat(breakers.circuitBreaker("project-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }
    @Test void disconnectedDependencyReturns503() {
        long before = repository.count().block();
        DISCONNECT.set(true);
        create(503);
        assertThat(repository.count().block()).isEqualTo(before);
    }
    private void create(int status) {
        var response = http.post().uri("/api/v1/experiments/{id}/runs", EXPERIMENT)
                .bodyValue(new CreateRunRequest(AUTHOR, "resilience-test")).exchange().expectStatus().isEqualTo(status);
        if (status != 201) response.expectBody().jsonPath("$.detail").value(value ->
                assertThat(value.toString()).doesNotContain("SECRET", "NoFallbackAvailable", "CallNotPermitted"));
    }
}
