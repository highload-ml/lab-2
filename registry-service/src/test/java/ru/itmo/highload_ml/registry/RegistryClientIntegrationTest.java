package ru.itmo.highload_ml.registry;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import ru.itmo.highload_ml.registry.api.dto.*;
import static org.assertj.core.api.Assertions.*;

/** Actual Feign proxies and circuits, JSON decoding, JPA and WebFlux; simulated upstream HTTP only. */
class RegistryClientIntegrationTest extends BaseIntegrationTest {
    static final UUID PROJECT = UUID.randomUUID(), USER = UUID.randomUUID(), EXPERIMENT = UUID.randomUUID();
    static final AtomicInteger PROJECT_STATUS = new AtomicInteger(200), TRACKING_STATUS = new AtomicInteger(200);
    static final AtomicInteger PROJECT_CALLS = new AtomicInteger(), TRACKING_CALLS = new AtomicInteger();
    static final AtomicBoolean SLOW_TRACKING = new AtomicBoolean();
    static final DisposableServer SERVER = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
        boolean artifact = request.uri().startsWith("/internal/artifacts/");
        (artifact ? TRACKING_CALLS : PROJECT_CALLS).incrementAndGet();
        int status = artifact ? TRACKING_STATUS.get() : PROJECT_STATUS.get();
        response.header("Content-Type", "application/json");
        if (status != 200) return response.status(status).sendString(Mono.just("SECRET upstream diagnostics"));
        String body;
        if (artifact) {
            String id = request.uri().substring("/internal/artifacts/".length());
            body = "{\"id\":\"" + id + "\",\"experimentId\":\"" + EXPERIMENT + "\",\"type\":\"MODEL\",\"runStatus\":\"COMPLETED\"}";
        } else if (request.uri().startsWith("/internal/experiments/")) {
            body = "{\"id\":\"" + EXPERIMENT + "\",\"projectId\":\"" + PROJECT + "\",\"active\":false}";
        } else if (request.uri().contains("/members/")) {
            body = "{\"projectId\":\"" + PROJECT + "\",\"userId\":\"" + USER + "\",\"role\":\"EDITOR\"}";
        } else { body = "{\"id\":\"" + PROJECT + "\"}"; }
        return response.sendString(artifact && SLOW_TRACKING.get()
                ? Mono.delay(Duration.ofSeconds(1)).map(ignored -> body) : Mono.just(body));
    }).bindNow();
    @DynamicPropertySource static void urls(DynamicPropertyRegistry properties) {
        properties.add("app.project-service.url", () -> "http://127.0.0.1:" + SERVER.port());
        properties.add("app.tracking-service.url", () -> "http://127.0.0.1:" + SERVER.port());
        properties.add("spring.cloud.openfeign.client.config.default.connectTimeout", () -> 100);
        properties.add("spring.cloud.openfeign.client.config.default.readTimeout", () -> 200);
    }
    @LocalServerPort int port;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired JdbcTemplate jdbc;
    WebTestClient http;
    String path = "/api/v1/projects/" + PROJECT + "/model-versions";

    @BeforeEach void resetDependency() {
        PROJECT_STATUS.set(200); TRACKING_STATUS.set(200); PROJECT_CALLS.set(0); TRACKING_CALLS.set(0); SLOW_TRACKING.set(false);
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll static void stop() { SERVER.disposeNow(); }
    @Test void registersHistoricalArtifactThroughActualFeignClients() {
        UUID artifact = UUID.randomUUID();
        var created = http.post().uri(path).bodyValue(new RegisterModelVersionRequest(USER, artifact)).exchange()
                .expectStatus().isCreated().expectBody(ModelVersionResponse.class).returnResult().getResponseBody();
        assertThat(created.artifactId()).isEqualTo(artifact);
        http.get().uri(path + "/" + created.id()).exchange().expectStatus().isOk();
    }
    @Test void trackingCircuitOpensIndependentlyAndRecovers() {
        long before = rows();
        TRACKING_STATUS.set(500);
        for (int i = 0; i < 5; i++) register(503);
        var tracking = breakers.circuitBreaker("tracking-service");
        assertThat(tracking.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breakers.circuitBreaker("project-service").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(TRACKING_CALLS.get()).isEqualTo(5);
        register(503);
        assertThat(TRACKING_CALLS.get()).isEqualTo(5);
        assertThat(rows()).isEqualTo(before);
        TRACKING_STATUS.set(200);
        tracking.transitionToHalfOpenState();
        register(201);
        assertThat(tracking.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        register(201);
        assertThat(tracking.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(rows()).isEqualTo(before + 2);
    }
    @Test void projectFailureStopsBeforeTrackingOrDatabase() {
        long before = rows();
        PROJECT_STATUS.set(500);
        for (int i = 0; i < 5; i++) register(503);
        assertThat(breakers.circuitBreaker("project-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        register(503);
        assertThat(PROJECT_CALLS.get()).isEqualTo(5);
        assertThat(TRACKING_CALLS.get()).isZero();
        assertThat(rows()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(ints = {404, 422})
    void expectedUpstreamErrorsDoNotOpenCircuit(int status) {
        TRACKING_STATUS.set(status);
        for (int i = 0; i < 8; i++) register(status);
        var circuit = breakers.circuitBreaker("tracking-service");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
    }
    @Test void timeoutDoesNotAllocateVersionOrInsert() {
        long before = rows();
        Long counter = jdbc.queryForObject("SELECT COALESCE(MAX(next_version), 0) FROM registry_version_counters WHERE project_id = ?", Long.class, PROJECT);
        SLOW_TRACKING.set(true);
        register(503);
        assertThat(rows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COALESCE(MAX(next_version), 0) FROM registry_version_counters WHERE project_id = ?", Long.class, PROJECT)).isEqualTo(counter);
        assertThat(breakers.circuitBreaker("tracking-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }
    private long rows() { return jdbc.queryForObject("SELECT count(*) FROM model_versions", Long.class); }
    private void register(int status) {
        var response = http.post().uri(path).bodyValue(new RegisterModelVersionRequest(USER, UUID.randomUUID()))
                .exchange().expectStatus().isEqualTo(status);
        if (status != 201) response.expectBody().jsonPath("$.detail").value(value ->
                assertThat(value.toString()).doesNotContain("SECRET", "NoFallbackAvailable", "CallNotPermitted"));
    }
}
