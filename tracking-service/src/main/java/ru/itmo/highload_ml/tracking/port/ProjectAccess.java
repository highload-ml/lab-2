package ru.itmo.highload_ml.tracking.port;
import java.util.UUID;
import reactor.core.publisher.Mono;
public interface ProjectAccess {
    Mono<Void> requireActiveExperiment(UUID experimentId);
    Mono<Void> requireAuthor(UUID experimentId, UUID authorId);
}
