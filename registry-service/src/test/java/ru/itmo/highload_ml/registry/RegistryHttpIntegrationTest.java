package ru.itmo.highload_ml.registry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.scheduler.Schedulers;
import ru.itmo.highload_ml.registry.api.dto.*;
import ru.itmo.highload_ml.registry.client.*;
import ru.itmo.highload_ml.registry.repository.RegistryCounterRepository;
import ru.itmo.highload_ml.registry.service.ModelVersionService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class RegistryHttpIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelVersionService versions;
    @MockitoBean ProjectClient projects;
    @MockitoBean TrackingClient tracking;
    @MockitoSpyBean RegistryCounterRepository counter;
    WebTestClient http;
    UUID project = UUID.randomUUID(), user = UUID.randomUUID(), experiment = UUID.randomUUID();
    String path;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE model_versions, registry_version_counters");
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(10)).build();
        path = "/api/v1/projects/" + project + "/model-versions";
        when(projects.project(project)).thenReturn(new ProjectClient.ProjectDetails(project));
        when(projects.membership(eq(project), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Schedulers.isInNonBlockingThread()).isFalse();
            return new ProjectClient.MembershipDetails(project, call.getArgument(1), "EDITOR");
        });
        when(projects.experiment(experiment)).thenReturn(new ProjectClient.ExperimentDetails(experiment, project, true));
        when(tracking.artifact(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TrackingClient.ArtifactDetails(call.getArgument(0), experiment, "MODEL", "COMPLETED");
        });
    }
    ModelVersionResponse register() {
        return http.post().uri(path).bodyValue(new RegisterModelVersionRequest(user, UUID.randomUUID())).exchange()
                .expectStatus().isCreated().expectHeader().exists("Location").expectBody(ModelVersionResponse.class).returnResult().getResponseBody();
    }
    void stage(UUID id) {
        http.post().uri(path + "/" + id + "/stage").bodyValue(new ModelActionRequest(user)).exchange().expectStatus().isOk();
    }
    void promote(UUID id) {
        http.post().uri(path + "/" + id + "/promote").bodyValue(new ModelActionRequest(user)).exchange().expectStatus().isOk();
    }
    @Test void fullLifecycleUsesRealTransactionsOnWorker() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(Thread.currentThread().getName()).startsWith("boundedElastic");
            return call.callRealMethod();
        }).when(counter).allocate(any());
        var first = register();
        stage(first.id()); promote(first.id());
        var second = register();
        stage(second.id()); promote(second.id());
        http.get().uri(path + "/production").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(second.id().toString());
        http.get().uri(path + "/" + first.id()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.state").isEqualTo("ARCHIVED");
        http.get().uri(path + "?size=1").exchange().expectStatus().isOk().expectHeader().valueEquals("X-Total-Count", "2")
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].version").isEqualTo(2);
        http.post().uri(path + "/" + first.id() + "/stage").bodyValue(new ModelActionRequest(user))
                .exchange().expectStatus().isEqualTo(422);
        http.post().uri(path).bodyValue(new RegisterModelVersionRequest(user, first.artifactId()))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.status").isEqualTo(409);
    }
    @Test void paginationHardLimitAndNotFoundValidation() {
        for (int i = 0; i < 51; i++) versions.register(project, UUID.randomUUID());
        http.get().uri(path + "?size=100").exchange().expectStatus().isOk().expectHeader().valueEquals("X-Total-Count", "51")
                .expectBody().jsonPath("$.length()").isEqualTo(50);
        http.get().uri(path + "/" + UUID.randomUUID()).exchange().expectStatus().isNotFound();
        http.post().uri(path + "/" + UUID.randomUUID() + "/stage").bodyValue(new ModelActionRequest(user))
                .exchange().expectStatus().isNotFound();
        http.post().uri(path).bodyValue(Map.of()).exchange().expectStatus().isBadRequest();
        http.get().uri(path + "?size=0").exchange().expectStatus().isBadRequest();
        http.get().uri(path + "?page=-1").exchange().expectStatus().isBadRequest();
        http.get().uri(path + "/not-a-uuid").exchange().expectStatus().isBadRequest();
    }
    @Test void upstreamFailuresAreSanitizedAndCreateNothing() {
        for (int status : new int[]{404, 422, 500, 503}) {
            doThrow(remote(status)).when(tracking).artifact(any());
            int expected = status < 500 ? status : 503;
            http.post().uri(path).bodyValue(new RegisterModelVersionRequest(user, UUID.randomUUID())).exchange()
                    .expectStatus().isEqualTo(expected).expectBody().jsonPath("$.detail").value(value -> assertThat(value.toString()).doesNotContain("SECRET"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_versions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registry_version_counters", Long.class)).isZero();
    }
    @Test void invalidArtifactReturns422BeforeAnyDatabaseChanges() {
        when(tracking.artifact(any())).thenAnswer(call -> new TrackingClient.ArtifactDetails(call.getArgument(0), experiment, "LOG", "COMPLETED"));
        http.post().uri(path).bodyValue(new RegisterModelVersionRequest(user, UUID.randomUUID())).exchange().expectStatus().isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_versions", Long.class)).isZero();
    }
    @Test void openApiExposesReactivePublicRoutes() {
        http.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.paths['/api/v1/projects/{projectId}/model-versions']").exists()
                .jsonPath("$.paths['/api/v1/projects/{projectId}/model-versions/{versionId}/promote']").exists();
    }
    private FeignException remote(int status) {
        var request = Request.create(Request.HttpMethod.GET, "http://tracking/internal", Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("artifact", Response.builder().request(request).status(status).reason("error")
                .body("SECRET internal diagnostics", StandardCharsets.UTF_8).build());
    }
}
