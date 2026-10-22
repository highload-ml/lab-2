package ru.itmo.highload_ml.tracking.repository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import ru.itmo.highload_ml.tracking.model.Run;

@Repository
public interface RunRepository extends ReactiveCrudRepository<Run, UUID> {
    Flux<Run> findByExperimentIdOrderByCreatedAtDescIdDesc(UUID experimentId, Pageable page);

    Flux<Run> findByExperimentIdAndCreatedAtAndIdLessThanOrderByIdDesc(
            UUID experimentId, Instant createdAt, UUID id, Pageable page);

    Flux<Run> findByExperimentIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            UUID experimentId, Instant createdAt, Pageable page);
}
