package ru.itmo.highload_ml.tracking.repository;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.model.Artifact;
@Repository
public interface ArtifactRepository extends ReactiveCrudRepository<Artifact, UUID> {
    Mono<Artifact> findByIdAndRunId(UUID id, UUID runId);
    Mono<Long> countByRunId(UUID runId);
    Mono<Boolean> existsByRunIdAndName(UUID runId, String name);
    @Query("SELECT * FROM artifacts WHERE run_id = :runId ORDER BY created_at, id LIMIT :limit OFFSET :offset")
    Flux<Artifact> findPage(UUID runId, int limit, long offset);
}
