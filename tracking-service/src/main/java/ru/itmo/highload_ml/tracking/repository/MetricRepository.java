package ru.itmo.highload_ml.tracking.repository;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.model.Metric;
@Repository
public interface MetricRepository extends ReactiveCrudRepository<Metric, UUID> {
    Mono<Metric> findByIdAndRunId(UUID id, UUID runId);
    Mono<Long> countByRunId(UUID runId);
    Mono<Boolean> existsByRunIdAndNameAndStep(UUID runId, String name, long step);
    @Query("SELECT * FROM metrics WHERE run_id = :runId ORDER BY recorded_at, id LIMIT :limit OFFSET :offset")
    Flux<Metric> findPage(UUID runId, int limit, long offset);
}
