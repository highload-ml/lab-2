package ru.itmo.highload_ml.tracking.repository;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload_ml.tracking.model.Metric;
@Repository
public interface MetricRepository extends ReactiveCrudRepository<Metric, UUID> {
    Mono<Metric> findByIdAndRunId(UUID id, UUID runId);
    Mono<Long> countByRunId(UUID runId);
    Mono<Boolean> existsByRunIdAndNameAndStep(UUID runId, String name, long step);
    Flux<Metric> findByRunId(UUID runId, Pageable pageable);
}
