package ru.itmo.highload_ml;

import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@org.junit.jupiter.api.Tag("integration")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(System.getenv().getOrDefault("TEST_POSTGRES_IMAGE", "postgres:17-alpine"))
            )
                    .withDatabaseName("project_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRESQL.start();
    }

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }
}