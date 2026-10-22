package ru.itmo.highload_ml.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.config.server.native.search-locations=file:../config/")
class ConfigServerTest {
    @LocalServerPort int port;
    @Test void servesSeparateApplicationConfigurationsFromNativeRepository() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String name : new String[]{"project-service", "tracking-service", "registry-service", "api-gateway"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + name + "/default")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"name\":\"" + name + "\"").contains(name + ".yml").contains("eureka.client.service-url.defaultZone");
                if (name.equals("tracking-service")) {
                    assertThat(response.body()).contains("spring.r2dbc.url").doesNotContain("spring.datasource.url");
                }
                if (name.equals("project-service")) {
                    assertThat(response.body()).contains("spring.datasource.url").doesNotContain("spring.r2dbc.url");
                }
            }
        }
    }
}
