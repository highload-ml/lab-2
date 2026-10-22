package ru.itmo.highload_ml.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {
    static final DisposableServer project = upstream("project");
    static final DisposableServer tracking = upstream("tracking");
    static final DisposableServer registry = upstream("registry");
    @LocalServerPort int port;

    static DisposableServer upstream(String name) {
        return HttpServer.create().port(0).handle((request, response) -> response
                .header("Location", "/api/v1/created")
                .header("X-Total-Count", "17").header("X-Next-Cursor", "next")
                .header("X-Has-Next", "true")
                .sendString(Mono.just(name + ":" + request.uri()))).bindNow();
    }
    @DynamicPropertySource static void discovery(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.discovery.client.simple.instances.project-service[0].uri", () -> "http://localhost:" + project.port());
        properties.add("spring.cloud.discovery.client.simple.instances.tracking-service[0].uri", () -> "http://localhost:" + tracking.port());
        properties.add("spring.cloud.discovery.client.simple.instances.registry-service[0].uri", () -> "http://localhost:" + registry.port());
    }
    @AfterAll static void stop() { project.disposeNow(); tracking.disposeNow(); registry.disposeNow(); }
    WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

    @Test void routesPublicApisWithoutChangingPathsOrPaginationHeaders() {
        for (String path : new String[]{"/api/v1/projects", "/api/v1/users", "/api/v1/tags", "/api/v1/projects/p/experiments", "/api/v1/projects/p/members/u"}) {
            client().get().uri(path).exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("project:" + path);
        }
        for (String path : new String[]{"/api/v1/runs/r/metrics", "/api/v1/runs/r/artifacts", "/api/v1/experiments/e/runs"}) {
            client().get().uri(path).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("Location", "/api/v1/created")
                    .expectHeader().valueEquals("X-Total-Count", "17")
                    .expectHeader().valueEquals("X-Next-Cursor", "next")
                    .expectHeader().valueEquals("X-Has-Next", "true")
                    .expectBody(String.class).isEqualTo("tracking:" + path);
        }
        for (String path : new String[]{"/api/v1/projects/p/model-versions", "/api/v1/projects/p/model-versions/production"}) {
            client().get().uri(path).exchange().expectStatus().isOk()
                    .expectBody(String.class).isEqualTo("registry:" + path);
        }
    }
    @Test void forwardsMutationMethodsAndQueryString() {
        client().post().uri("/api/v1/projects").bodyValue("{}").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("project:/api/v1/projects");
        client().delete().uri("/api/v1/projects/p/experiments/e").exchange().expectStatus().isOk();
        client().get().uri("/api/v1/runs/r/metrics?page=2&size=5").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("tracking:/api/v1/runs/r/metrics?page=2&size=5");
    }
    @Test void exposesEachSwaggerDocumentAndBlocksInternalNamespaces() {
        for (String name : new String[]{"project", "tracking", "registry"}) {
            client().get().uri("/v3/api-docs/" + name).exchange().expectStatus().isOk()
                    .expectBody(String.class).isEqualTo(name + ":/v3/api-docs");
        }
        client().get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
        for (String path : new String[]{"/internal/projects/p", "/project-service/internal/projects/p", "/api/v1/internal/projects/p", "/api/v1/projects/p/internal"}) {
            client().get().uri(path).exchange().expectStatus().isNotFound();
        }
    }
}
