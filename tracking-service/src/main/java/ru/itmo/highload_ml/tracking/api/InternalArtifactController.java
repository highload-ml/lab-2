package ru.itmo.highload_ml.tracking.api;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.service.ArtifactService;
@Hidden
@RestController
@RequestMapping("/internal/artifacts")
@RequiredArgsConstructor
public class InternalArtifactController {
    private final ArtifactService service;
    @GetMapping("/{id}")
    public Mono<ArtifactService.ArtifactDetails> details(@PathVariable UUID id) { return service.details(id); }
}
