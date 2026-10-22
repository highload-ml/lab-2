package ru.itmo.highload_ml.registry;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;
import ru.itmo.highload_ml.registry.api.*;
import ru.itmo.highload_ml.registry.api.dto.*;
import ru.itmo.highload_ml.registry.model.ModelVersionState;
import ru.itmo.highload_ml.registry.service.RegistryService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ModelVersionControllerTest {
    RegistryService service = mock(RegistryService.class);
    WebTestClient http;
    UUID project = UUID.randomUUID();
    ModelVersionResponse version = new ModelVersionResponse(UUID.randomUUID(), project, UUID.randomUUID(), 1,
            ModelVersionState.NEW, Instant.now(), null);
    String path = "/api/v1/projects/" + project + "/model-versions";

    @BeforeEach void setup() {
        http = WebTestClient.bindToController(new ModelVersionController(service, new RegistryPagination(100)))
                .controllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void defersBlockingWorkToWorkerAndReturnsLocation() {
        var worker = new AtomicReference<String>();
        when(service.register(eq(project), any())).thenAnswer(call -> {
            assertThat(Schedulers.isInNonBlockingThread()).isFalse();
            worker.set(Thread.currentThread().getName());
            return version;
        });
        http.post().uri(path).bodyValue(new RegisterModelVersionRequest(UUID.randomUUID(), version.artifactId()))
                .exchange().expectStatus().isCreated().expectHeader().valueEquals("Location", path + "/" + version.id());
        assertThat(worker.get()).startsWith("boundedElastic");
    }
    @Test void listsAsArrayWithCountAndCapsSizeAtFifty() {
        when(service.findAll(eq(project), any())).thenAnswer(call -> {
            Pageable pageable = call.getArgument(1);
            assertThat(pageable.getPageSize()).isEqualTo(50);
            assertThat(pageable.getSort().getOrderFor("version").isDescending()).isTrue();
            return new PageImpl<>(java.util.List.of(version), pageable, 1);
        });
        http.get().uri(path + "?size=100").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "1").expectBody().jsonPath("$.length()").isEqualTo(1);
    }
    @Test void validatesInputAndPaginationBeforeCallingService() {
        http.post().uri(path).bodyValue(Map.of()).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.validationErrors.artifactId").exists();
        http.get().uri(path + "?page=-1").exchange().expectStatus().isBadRequest();
        http.get().uri(path + "?size=0").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(service);
        assertThatThrownBy(() -> new RegistryPagination(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RegistryPagination(5).pageRequest(0, 100).getPageSize()).isEqualTo(5);
    }
    @Test void hidesUnexpectedFailureDetails() {
        when(service.getById(project, version.id())).thenThrow(new IllegalStateException("secret DB credentials"));
        http.get().uri(path + "/" + version.id()).exchange().expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.detail").isEqualTo("An unexpected error occurred");
    }
}
