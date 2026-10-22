package ru.itmo.highload_ml.tracking;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.itmo.highload_ml.tracking.port.ProjectAccess;
import ru.itmo.highload_ml.tracking.model.*;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.repository.*;
import ru.itmo.highload_ml.tracking.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TrackingIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired RunRepository runs;
    @Autowired MetricRepository metrics;
    @Autowired ArtifactRepository artifacts;
    @Autowired RunService runService;
    @Autowired MetricService metricService;
    @Autowired ArtifactService artifactService;
    @Autowired DatabaseClient db;
    @MockitoBean ProjectAccess projects;
    WebTestClient http;
    UUID experiment = UUID.randomUUID();
    UUID author = UUID.randomUUID();
    @BeforeEach void prepare() {
        db.sql("TRUNCATE metrics, artifacts, runs").then().block(Duration.ofSeconds(10));
        when(projects.requireAuthor(any(), any())).thenReturn(Mono.empty());
        when(projects.requireActiveExperiment(any())).thenReturn(Mono.empty());
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(10)).build();
    }
    RunResponse create() { return runService.create(experiment, new CreateRunRequest(author, "run")).block(Duration.ofSeconds(10)); }
    RunResponse running() { RunResponse run = create(); return runService.start(run.id()).block(Duration.ofSeconds(10)); }
    CreateMetricRequest metric(String name, long step) { return new CreateMetricRequest(name, new BigDecimal("0.12345678"), step); }

    @Test void completeHttpLifecycleAndInternalLookup() {
        var created = http.post().uri("/api/v1/experiments/{id}/runs", experiment)
                .bodyValue(new CreateRunRequest(author, "baseline")).exchange().expectStatus().isCreated()
                .expectHeader().exists("Location").expectBody(RunResponse.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();
        String runPath = "/api/v1/runs/" + created.id();
        http.post().uri(runPath + "/start").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("RUNNING");
        var savedMetric = http.post().uri(runPath + "/metrics").bodyValue(metric("loss", 0)).exchange()
                .expectStatus().isCreated().expectHeader().exists("Location").expectBody(MetricResponse.class).returnResult().getResponseBody();
        http.get().uri(runPath + "/metrics/" + savedMetric.id()).exchange().expectStatus().isOk();
        var savedArtifact = http.post().uri(runPath + "/artifacts")
                .bodyValue(new CreateArtifactRequest("model", ArtifactType.MODEL, "local/model.bin", 42L))
                .exchange().expectStatus().isCreated().expectHeader().exists("Location")
                .expectBody(ArtifactResponse.class).returnResult().getResponseBody();
        http.get().uri(runPath + "/artifacts/" + savedArtifact.id()).exchange().expectStatus().isOk();
        http.get().uri(runPath + "/artifacts?size=1").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "1").expectBody().jsonPath("$.length()").isEqualTo(1);
        http.post().uri(runPath + "/complete").exchange().expectStatus().isOk();
        http.get().uri("/internal/artifacts/" + savedArtifact.id()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.runStatus").isEqualTo("COMPLETED").jsonPath("$.experimentId").isEqualTo(experiment.toString());
        http.post().uri(runPath + "/metrics").bodyValue(metric("loss", 1)).exchange().expectStatus().isEqualTo(422);
        http.post().uri(runPath + "/artifacts").bodyValue(new CreateArtifactRequest("other", ArtifactType.OTHER, "a", 1L))
                .exchange().expectStatus().isEqualTo(422);
        http.post().uri(runPath + "/start").exchange().expectStatus().isEqualTo(422);
        http.get().uri(runPath).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("COMPLETED");
    }
    @Test void duplicateInBatchRollsBackEarlierInsertAndEmitsNoPartialResponse() {
        var run = running();
        http.post().uri("/api/v1/runs/{id}/metrics/batch", run.id())
                .bodyValue(new CreateMetricsRequest(List.of(metric("loss", 0), metric("loss", 0))))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.status").isEqualTo(409);
        assertThat(metrics.countByRunId(run.id()).block()).isZero();
    }
    @Test void successfulBatchAndClassicPagination() {
        var run = running();
        var batch = java.util.stream.IntStream.range(0, 60).mapToObj(i -> metric("loss", i)).toList();
        http.post().uri("/api/v1/runs/{id}/metrics/batch", run.id()).bodyValue(new CreateMetricsRequest(batch))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.length()").isEqualTo(60);
        http.get().uri("/api/v1/runs/{id}/metrics?size=100", run.id()).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "60").expectBody().jsonPath("$.length()").isEqualTo(50)
                .jsonPath("$[0].step").isEqualTo(0).jsonPath("$[49].step").isEqualTo(49);
        http.get().uri("/api/v1/runs/{id}/metrics?size=50&page=1", run.id()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(10)
                .jsonPath("$[0].step").isEqualTo(50).jsonPath("$[9].step").isEqualTo(59);
    }
    @Test void keysetHasNoOverlapEvenWhenTimestampsMatch() {
        for (int i = 0; i < 3; i++) create();
        db.sql("UPDATE runs SET created_at = '2026-01-01T00:00:00Z'").then().block();
        var older = create();
        db.sql("UPDATE runs SET created_at = '2025-12-31T00:00:00Z' WHERE id = :id")
                .bind("id", older.id()).then().block();
        var first = http.get().uri("/api/v1/experiments/{id}/runs?size=2", experiment).exchange()
                .expectStatus().isOk().expectHeader().doesNotExist("X-Total-Count")
                .expectHeader().valueEquals("X-Has-Next", "true").expectBodyList(RunResponse.class).returnResult();
        assertThat(first.getResponseBody()).hasSize(2);
        String cursor = first.getResponseHeaders().getFirst("X-Next-Cursor");
        var second = http.get().uri("/api/v1/experiments/{id}/runs?size=2&after={cursor}", experiment, cursor).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("X-Has-Next", "false")
                .expectHeader().doesNotExist("X-Next-Cursor").expectBodyList(RunResponse.class).returnResult();
        assertThat(second.getResponseBody()).hasSize(2).doesNotContainAnyElementsOf(first.getResponseBody());
        assertThat(second.getResponseBody().get(1).id()).isEqualTo(older.id());
    }
    @Test void repeatedStartIsRejected() {
        var run = create();
        runService.start(run.id()).block(Duration.ofSeconds(10));
        reactor.test.StepVerifier.create(runService.start(run.id()))
                .expectError(ru.itmo.highload_ml.tracking.exception.InvalidRunStateTransitionException.class).verify();
    }
    @Test void completedRunRejectsMetricBatch() {
        var run = running();
        runService.complete(run.id()).block(Duration.ofSeconds(10));
        var request = new CreateMetricsRequest(List.of(metric("loss", 0)));
        reactor.test.StepVerifier.create(metricService.logBatch(run.id(), request))
                .expectError(ru.itmo.highload_ml.tracking.exception.RunNotRunningException.class).verify();
        assertThat(metrics.countByRunId(run.id()).block()).isZero();
    }
    @Test void uniqueArtifactAndScopedReads() {
        var run = running();
        var request = new CreateArtifactRequest("model", ArtifactType.MODEL, "a", 1L);
        var saved = artifactService.register(run.id(), request).block();
        http.post().uri("/api/v1/runs/{id}/artifacts", run.id()).bodyValue(request).exchange().expectStatus().isEqualTo(409);
        artifactService.register(run.id(), new CreateArtifactRequest("second-model", ArtifactType.MODEL, "b", 2L)).block();
        http.get().uri("/api/v1/runs/{id}/artifacts?size=1&page=0", run.id()).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "2").expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].name").isEqualTo("model");
        http.get().uri("/api/v1/runs/{id}/artifacts?size=1&page=1", run.id()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].name").isEqualTo("second-model");
        var other = create();
        http.get().uri("/api/v1/runs/{r}/artifacts/{a}", other.id(), saved.id()).exchange().expectStatus().isNotFound();
        http.get().uri("/internal/artifacts/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }
    @Test void inputValidationAndNotFoundStayProblemDetails() {
        var run = create();
        String path = "/api/v1/runs/" + run.id();
        http.post().uri("/api/v1/experiments/{id}/runs", experiment).bodyValue(Map.of("name", " ")).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.validationErrors.authorId").exists();
        http.post().uri(path + "/metrics").bodyValue(Map.of("name", "loss", "value", "999999999999", "step", -1))
                .exchange().expectStatus().isBadRequest();
        http.post().uri(path + "/metrics/batch").bodyValue(Map.of("metrics", List.of())).exchange().expectStatus().isBadRequest();
        http.post().uri(path + "/metrics/batch").bodyValue(Map.of("metrics", java.util.Arrays.asList((Object) null)))
                .header("Content-Type", "application/json").exchange().expectStatus().isBadRequest();
        http.get().uri(path + "/metrics?page=-1").exchange().expectStatus().isBadRequest();
        http.get().uri(path + "/artifacts?size=0").exchange().expectStatus().isBadRequest();
        http.get().uri("/api/v1/experiments/{id}/runs?after=invalid", experiment).exchange().expectStatus().isBadRequest();
        http.get().uri("/api/v1/runs/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
        http.get().uri("/api/v1/runs/not-a-uuid").exchange().expectStatus().isBadRequest();
    }
    @Test void schemaEnforcesLocalForeignKeysChecksAndStringEnums() {
        var run = running();
        assertThat(db.sql("SELECT status FROM runs WHERE id = :id").bind("id", run.id()).map(row -> row.get("status", String.class)).one().block())
                .isEqualTo("RUNNING");
        StepVerifier.create(db.sql("UPDATE runs SET status = 'BROKEN' WHERE id = :id").bind("id", run.id()).then()).expectError().verify();
        StepVerifier.create(metrics.save(new Metric(UUID.randomUUID(), "loss", BigDecimal.ONE, 0))).expectError().verify();
        StepVerifier.create(artifacts.save(new Artifact(run.id(), "bad", ArtifactType.MODEL, "a", -1))).expectError().verify();
    }
    @Test void openApiContainsTrackingRoutesButNotInternalRoutes() {
        http.get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.paths['/api/v1/runs/{runId}/metrics/batch']").exists()
                .jsonPath("$.paths['/internal/artifacts/{id}']").doesNotExist();
    }
    @Test void failedProjectCheckCreatesNothing() {
        when(projects.requireAuthor(any(), any())).thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Project service unavailable")));
        http.post().uri("/api/v1/experiments/{id}/runs", experiment).bodyValue(new CreateRunRequest(author, "run"))
                .exchange().expectStatus().isEqualTo(503);
        assertThat(runs.count().block()).isZero();
    }
}
