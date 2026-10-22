package ru.itmo.highload_ml.registry;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import ru.itmo.highload_ml.registry.api.dto.*;
import static org.assertj.core.api.Assertions.*;

/** Actual Feign proxies, DTO JSON decoding, JPA and WebFlux; only remote HTTP servers are simulated. */
class RegistryClientIntegrationTest extends BaseIntegrationTest {
    static final UUID PROJECT = UUID.randomUUID(), USER = UUID.randomUUID(), EXPERIMENT = UUID.randomUUID();
    static final DisposableServer SERVER = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
        response.header("Content-Type", "application/json");
        String body;
        if (request.uri().startsWith("/internal/artifacts/")) {
            String id = request.uri().substring("/internal/artifacts/".length());
            body = "{\"id\":\"" + id + "\",\"experimentId\":\"" + EXPERIMENT + "\",\"type\":\"MODEL\",\"runStatus\":\"COMPLETED\"}";
        } else if (request.uri().startsWith("/internal/experiments/")) {
            body = "{\"id\":\"" + EXPERIMENT + "\",\"projectId\":\"" + PROJECT + "\",\"active\":false}";
        } else if (request.uri().contains("/members/")) {
            body = "{\"projectId\":\"" + PROJECT + "\",\"userId\":\"" + USER + "\",\"role\":\"EDITOR\"}";
        } else { body = "{\"id\":\"" + PROJECT + "\"}"; }
        return response.sendString(Mono.just(body));
    }).bindNow();
    @DynamicPropertySource static void urls(DynamicPropertyRegistry properties) {
        properties.add("app.project-service.url", () -> "http://127.0.0.1:" + SERVER.port());
        properties.add("app.tracking-service.url", () -> "http://127.0.0.1:" + SERVER.port());
    }
    @LocalServerPort int port;
    @AfterAll static void stop() { SERVER.disposeNow(); }
    @Test void registersHistoricalArtifactThroughActualFeignClients() {
        var http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(10)).build();
        UUID artifact = UUID.randomUUID();
        String path = "/api/v1/projects/" + PROJECT + "/model-versions";
        var created = http.post().uri(path).bodyValue(new RegisterModelVersionRequest(USER, artifact)).exchange()
                .expectStatus().isCreated().expectBody(ModelVersionResponse.class).returnResult().getResponseBody();
        assertThat(created.artifactId()).isEqualTo(artifact);
        http.get().uri(path + "/" + created.id()).exchange().expectStatus().isOk();
    }
}
