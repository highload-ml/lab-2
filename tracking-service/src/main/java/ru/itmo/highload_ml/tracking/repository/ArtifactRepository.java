package ru.itmo.highload_ml.tracking.repository;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.model.Artifact;
@Repository
public interface ArtifactRepository extends ReactiveCrudRepository<Artifact, UUID> {
    Mono<Artifact> findByIdAndRunId(UUID id, UUID runId);
    Mono<Long> countByRunId(UUID runId);
    Mono<Boolean> existsByRunIdAndName(UUID runId, String name);
    Flux<Artifact> findByRunId(UUID runId, Pageable pageable);
}
