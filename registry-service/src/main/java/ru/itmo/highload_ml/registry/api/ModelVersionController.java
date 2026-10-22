package ru.itmo.highload_ml.registry.api;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Callable;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import ru.itmo.highload_ml.registry.api.dto.*;
import ru.itmo.highload_ml.registry.service.RegistryService;

@RestController
@RequestMapping(value = "/api/v1/projects/{projectId}/model-versions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Model versions")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "404", description = "Project, artifact or version not found"),
        @ApiResponse(responseCode = "422", description = "Membership, artifact or state rule violated"),
        @ApiResponse(responseCode = "503", description = "Project or tracking service unavailable")
})
@RequiredArgsConstructor
public class ModelVersionController {
    private final RegistryService service;
    private final RegistryPagination pagination;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a MODEL artifact from a completed run")
    @ApiResponse(responseCode = "201", description = "Registered; Location points to the version")
    @ApiResponse(responseCode = "409", description = "Artifact already registered")
    public Mono<ModelVersionResponse> register(@PathVariable UUID projectId,
                                               @Valid @RequestBody RegisterModelVersionRequest request,
                                               ServerHttpResponse response) {
        return blocking(() -> service.register(projectId, request)).doOnNext(created -> response.getHeaders()
                .setLocation(URI.create("/api/v1/projects/" + projectId + "/model-versions/" + created.id())));
    }

    @GetMapping("/{versionId}")
    @Operation(summary = "Get a model version")
    @ApiResponse(responseCode = "200", description = "Version found")
    public Mono<ModelVersionResponse> get(@PathVariable UUID projectId, @PathVariable UUID versionId) {
        return blocking(() -> service.getById(projectId, versionId));
    }

    @GetMapping
    @Operation(summary = "List versions newest first", description = "JSON array, maximum 50 items; total in X-Total-Count")
    @ApiResponse(responseCode = "200", description = "Page and total count header")
    public Flux<ModelVersionResponse> findAll(@PathVariable UUID projectId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             ServerHttpResponse response) {
        var pageable = pagination.pageRequest(page, size);
        return blocking(() -> service.findAll(projectId, pageable)).flatMapMany(result -> {
            response.getHeaders().set("X-Total-Count", Long.toString(result.getTotalElements()));
            return Flux.fromIterable(result.getContent());
        });
    }

    @GetMapping("/production")
    @Operation(summary = "Get current production version")
    @ApiResponse(responseCode = "200", description = "Production version found")
    public Mono<ModelVersionResponse> production(@PathVariable UUID projectId) {
        return blocking(() -> service.getProduction(projectId));
    }

    @PostMapping("/{versionId}/stage")
    @Operation(summary = "Move NEW to STAGING")
    @ApiResponse(responseCode = "200", description = "Version staged")
    public Mono<ModelVersionResponse> stage(@PathVariable UUID projectId, @PathVariable UUID versionId,
                                            @Valid @RequestBody ModelActionRequest request) {
        return blocking(() -> service.stage(projectId, versionId, request.userId()));
    }

    @PostMapping("/{versionId}/promote")
    @Operation(summary = "Publish STAGING version and archive previous production")
    @ApiResponse(responseCode = "200", description = "Version promoted atomically")
    public Mono<ModelVersionResponse> promote(@PathVariable UUID projectId, @PathVariable UUID versionId,
                                              @Valid @RequestBody ModelActionRequest request) {
        return blocking(() -> service.promote(projectId, versionId, request.userId()));
    }

    /** The sole Reactor boundary: Feign and JPA run on a worker, including the transactional proxy call. */
    private <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
