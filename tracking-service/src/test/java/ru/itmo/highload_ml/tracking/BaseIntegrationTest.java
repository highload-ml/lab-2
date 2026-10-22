package ru.itmo.highload_ml.tracking;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
            System.getenv().getOrDefault("TEST_POSTGRES_IMAGE", "postgres:17-alpine")))
            .withDatabaseName("tracking_test").withUsername("test").withPassword("test");
    static { POSTGRES.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/tracking_test");
        properties.add("spring.r2dbc.username", POSTGRES::getUsername);
        properties.add("spring.r2dbc.password", POSTGRES::getPassword);
        properties.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        properties.add("spring.liquibase.user", POSTGRES::getUsername);
        properties.add("spring.liquibase.password", POSTGRES::getPassword);
    }
}
