package ru.itmo.highload_ml.tracking.repository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.model.Run;

@Repository
public interface RunRepository extends ReactiveCrudRepository<Run, UUID> {
    @Query("SELECT * FROM runs WHERE id = :id FOR UPDATE")
    Mono<Run> findLocked(UUID id);

    @Query("SELECT * FROM runs WHERE experiment_id = :experimentId ORDER BY created_at DESC, id DESC LIMIT :limit")
    Flux<Run> findFirstBatch(UUID experimentId, int limit);

    @Query("SELECT * FROM runs WHERE experiment_id = :experimentId AND (created_at, id) < (:createdAt, :id) ORDER BY created_at DESC, id DESC LIMIT :limit")
    Flux<Run> findBefore(UUID experimentId, Instant createdAt, UUID id, int limit);
}
