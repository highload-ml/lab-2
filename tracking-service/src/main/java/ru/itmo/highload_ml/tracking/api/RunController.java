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
import ru.itmo.highload_ml.tracking.service.*;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Runs")
@RequiredArgsConstructor
public class RunController {
    private final RunService service;
    private final TrackingPagination pagination;

    @PostMapping("/experiments/{experimentId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create run after checking experiment activity and author membership")
    @ApiResponse(responseCode = "201", description = "Run created; Location points to its resource")
    @ApiResponse(responseCode = "404", description = "Experiment not found or inactive")
    @ApiResponse(responseCode = "422", description = "Author is not a member")
    @ApiResponse(responseCode = "503", description = "Project service unavailable")
    public Mono<RunResponse> create(@PathVariable UUID experimentId, @Valid @RequestBody CreateRunRequest request,
                                    ServerHttpResponse response) {
        return service.create(experimentId, request).doOnNext(created -> response.getHeaders()
                .setLocation(URI.create("/api/v1/runs/" + created.id())));
    }
    @GetMapping("/experiments/{experimentId}/runs")
    @Operation(summary = "Scroll runs without COUNT", description = "JSON array, at most 50 items. X-Has-Next and X-Next-Cursor contain navigation; pass cursor as after.")
    @ApiResponse(responseCode = "200", description = "Run array and cursor headers")
    @ApiResponse(responseCode = "400", description = "Invalid cursor or size")
    public Flux<RunResponse> findAll(@PathVariable UUID experimentId, @RequestParam(required = false) String after,
                                    @RequestParam(defaultValue = "20") int size, ServerHttpResponse response) {
        int limit = pagination.size(size);
        RunCursor cursor = after == null ? null : RunCursorCodec.decode(after, experimentId);
        return service.findAll(experimentId, cursor, limit + 1).collectList().flatMapMany(rows -> {
            boolean hasNext = rows.size() > limit;
            response.getHeaders().set("X-Has-Next", Boolean.toString(hasNext));
            if (hasNext) {
                RunResponse last = rows.get(limit - 1);
                response.getHeaders().set("X-Next-Cursor", RunCursorCodec.encode(experimentId, new RunCursor(last.createdAt(), last.id())));
            }
            return Flux.fromIterable(rows.subList(0, Math.min(limit, rows.size())));
        });
    }
    @GetMapping("/runs/{runId}")
    @Operation(summary = "Get run")
    @ApiResponse(responseCode = "200", description = "Run found")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public Mono<RunResponse> get(@PathVariable UUID runId) { return service.getById(runId); }
    @PostMapping("/runs/{runId}/start")
    @Operation(summary = "Start CREATED run")
    @ApiResponse(responseCode = "200", description = "Run started")
    @ApiResponse(responseCode = "422", description = "Invalid transition")
    public Mono<RunResponse> start(@PathVariable UUID runId) { return service.start(runId); }
    @PostMapping("/runs/{runId}/complete")
    @Operation(summary = "Complete RUNNING run")
    @ApiResponse(responseCode = "200", description = "Run completed")
    @ApiResponse(responseCode = "422", description = "Invalid transition")
    public Mono<RunResponse> complete(@PathVariable UUID runId) { return service.complete(runId); }
    @PostMapping("/runs/{runId}/fail")
    @Operation(summary = "Fail RUNNING run")
    @ApiResponse(responseCode = "200", description = "Run failed")
    @ApiResponse(responseCode = "422", description = "Invalid transition")
    public Mono<RunResponse> fail(@PathVariable UUID runId) { return service.fail(runId); }
}
