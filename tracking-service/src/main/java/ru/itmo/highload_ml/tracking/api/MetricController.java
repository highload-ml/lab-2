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
import ru.itmo.highload_ml.tracking.service.MetricService;

@RestController
@RequestMapping(value = "/api/v1/runs/{runId}/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Metrics")
@RequiredArgsConstructor
public class MetricController {
    private final MetricService service;
    private final TrackingPagination pagination;
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Log metric")
    @ApiResponse(responseCode = "201", description = "Created; resource URI is in Location")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "409", description = "Duplicate")
    @ApiResponse(responseCode = "422", description = "Run must be RUNNING")
    public Mono<MetricResponse> log(@PathVariable UUID runId, @Valid @RequestBody CreateMetricRequest request, ServerHttpResponse response) {
        return service.log(runId, request).doOnNext(created -> response.getHeaders()
                .setLocation(URI.create("/api/v1/runs/" + runId + "/metrics/" + created.id())));
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Log 1 to 100 metrics atomically")
    @ApiResponse(responseCode = "201", description = "Batch committed")
    @ApiResponse(responseCode = "409", description = "Duplicate metric; entire batch rolled back")
    public Flux<MetricResponse> logBatch(@PathVariable UUID runId, @Valid @RequestBody CreateMetricsRequest request) {
        return service.logBatch(runId, request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get metric in run")
    @ApiResponse(responseCode = "200", description = "Found")
    @ApiResponse(responseCode = "404", description = "Run or metric not found")
    public Mono<MetricResponse> get(@PathVariable UUID runId, @PathVariable UUID id) { return service.getById(runId, id); }
    @GetMapping
    @Operation(summary = "List metrics", description = "JSON array, maximum 50 items. Total count in X-Total-Count.")
    @ApiResponse(responseCode = "200", description = "Page and total count header")
    @ApiResponse(responseCode = "400", description = "Invalid pagination")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public Flux<MetricResponse> findAll(@PathVariable UUID runId, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size, ServerHttpResponse response) {
        var pageable = pagination.page(page, size, "recordedAt", "id");
        return service.count(runId).flatMapMany(count -> {
            response.getHeaders().set("X-Total-Count", Long.toString(count));
            return service.findAll(runId, pageable);
        });
    }
}
