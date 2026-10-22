package ru.itmo.highload_ml.tracking.api;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.service.RunService;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.model.RunStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RunControllerTest {
    RunService service = mock(RunService.class);
    WebTestClient http;
    UUID experiment = UUID.randomUUID(), id = UUID.randomUUID();
    RunResponse run = new RunResponse(id, experiment, UUID.randomUUID(), "run", RunStatus.CREATED, Instant.now(), null, null);
    @BeforeEach void setup() {
        http = WebTestClient.bindToController(new RunController(service, new TrackingPagination(100)))
                .controllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void exposesCursorHeadersAndNeverLeaksExtraRow() {
        when(service.findAll(eq(experiment), isNull(), eq(3))).thenReturn(Flux.just(run, run, run));
        http.get().uri("/api/v1/experiments/{id}/runs?size=2", experiment).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Has-Next", "true").expectHeader().exists("X-Next-Cursor")
                .expectHeader().doesNotExist("X-Total-Count").expectBody().jsonPath("$.length()").isEqualTo(2);
    }
    @Test void unexpectedErrorsAreSanitized() {
        when(service.getById(id)).thenReturn(Mono.error(new IllegalStateException("secret database password")));
        http.get().uri("/api/v1/runs/{id}", id).exchange().expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.detail").isEqualTo("An unexpected error occurred");
    }
    @Test void validationIncludesFieldErrors() {
        http.post().uri("/api/v1/experiments/{id}/runs", experiment).bodyValue(java.util.Map.of("name", " "))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.validationErrors.authorId").exists();
        verifyNoInteractions(service);
    }
    @Test void createsResourceWithLocation() {
        when(service.create(eq(experiment), any())).thenReturn(Mono.just(run));
        http.post().uri("/api/v1/experiments/{id}/runs", experiment).bodyValue(new CreateRunRequest(UUID.randomUUID(), "run"))
                .exchange().expectStatus().isCreated().expectHeader().valueEquals("Location", "/api/v1/runs/" + id);
    }
    @Test void rejectsInvalidCursorBeforeCallingService() {
        http.get().uri("/api/v1/experiments/{id}/runs?after=invalid", experiment).exchange().expectStatus().isBadRequest();
        verifyNoInteractions(service);
    }
}
