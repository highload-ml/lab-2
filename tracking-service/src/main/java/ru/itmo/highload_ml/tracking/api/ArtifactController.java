package ru.itmo.highload_ml.tracking.api;
import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.service.ArtifactService;

@RestController
@RequestMapping(value = "/api/v1/runs/{runId}/artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Artifacts")
@RequiredArgsConstructor
public class ArtifactController {
    private final ArtifactService service;
    private final TrackingPagination pagination;
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register artifact metadata (no file upload)")
    @ApiResponse(responseCode = "201", description = "Created; resource URI is in Location")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "409", description = "Duplicate")
    @ApiResponse(responseCode = "422", description = "Run must be RUNNING")
    public Mono<ArtifactResponse> register(@PathVariable UUID runId, @Valid @RequestBody CreateArtifactRequest request, ServerHttpResponse response) {
        return service.register(runId, request).doOnNext(created -> response.getHeaders()
                .setLocation(URI.create("/api/v1/runs/" + runId + "/artifacts/" + created.id())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get artifact in run")
    @ApiResponse(responseCode = "200", description = "Found")
    @ApiResponse(responseCode = "404", description = "Run or artifact not found")
    public Mono<ArtifactResponse> get(@PathVariable UUID runId, @PathVariable UUID id) { return service.getById(runId, id); }
    @GetMapping
    @Operation(summary = "List artifacts", description = "JSON array, maximum 50 items. Total count in X-Total-Count.")
    @ApiResponse(responseCode = "200", description = "Page and total count header")
    @ApiResponse(responseCode = "400", description = "Invalid pagination")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public Flux<ArtifactResponse> findAll(@PathVariable UUID runId, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size, ServerHttpResponse response) {
        int limit = pagination.size(size);
        long offset = pagination.offset(page, size);
        return service.count(runId).flatMapMany(count -> {
            response.getHeaders().set("X-Total-Count", Long.toString(count));
            return service.findAll(runId, limit, offset);
        });
    }
}
