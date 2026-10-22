package ru.itmo.highload_ml.tracking;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import ru.itmo.highload_ml.tracking.api.dto.CreateRunRequest;
import ru.itmo.highload_ml.tracking.model.RunStatus;
import ru.itmo.highload_ml.tracking.port.ProjectAccess;
import ru.itmo.highload_ml.tracking.service.RunService;
import static org.assertj.core.api.Assertions.*;

/** Uses the actual Spring Feign proxy and JSON decoder, not a mocked project port. */
class ProjectClientIntegrationTest extends BaseIntegrationTest {
    static final UUID EXPERIMENT = UUID.randomUUID();
    static final UUID INACTIVE = UUID.randomUUID();
    static final UUID PROJECT = UUID.randomUUID();
    static final UUID AUTHOR = UUID.randomUUID();
    static final DisposableServer SERVER = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                response.header("Content-Type", "application/json");
                if (request.uri().contains("/members/")) {
                    return response.sendString(Mono.just("{\"projectId\":\"" + PROJECT + "\",\"userId\":\"" + AUTHOR + "\",\"role\":\"EDITOR\"}"));
                }
                boolean active = !request.uri().endsWith(INACTIVE.toString());
                return response.sendString(Mono.just("{\"id\":\"" + (active ? EXPERIMENT : INACTIVE)
                        + "\",\"projectId\":\"" + PROJECT + "\",\"active\":" + active + "}"));
            }).bindNow();
    @DynamicPropertySource static void projectUrl(DynamicPropertyRegistry properties) {
        properties.add("app.project-service.url", () -> "http://127.0.0.1:" + SERVER.port());
    }
    @Autowired RunService runs;
    @Autowired ProjectAccess projects;
    @AfterAll static void stop() { SERVER.disposeNow(); }

    @Test void realFeignProxyDecodesProjectDtosAndAllowsCreation() {
        var created = runs.create(EXPERIMENT, new CreateRunRequest(AUTHOR, "real-feign")).block(Duration.ofSeconds(10));
        assertThat(created.id()).isNotNull();
        assertThat(created.experimentId()).isEqualTo(EXPERIMENT);
        assertThat(created.status()).isEqualTo(RunStatus.CREATED);
    }
    @Test void realFeignProxyRejectsSoftDeletedExperiment() {
        StepVerifier.create(projects.requireAuthor(INACTIVE, AUTHOR)).expectErrorSatisfies(error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404)).verify(Duration.ofSeconds(10));
    }
}
