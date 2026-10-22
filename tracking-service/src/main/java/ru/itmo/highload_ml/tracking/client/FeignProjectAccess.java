package ru.itmo.highload_ml.tracking.client;
import java.util.UUID;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.itmo.highload_ml.tracking.port.ProjectAccess;

@Component
@RequiredArgsConstructor
public class FeignProjectAccess implements ProjectAccess {
    private final ProjectClient client;
    public Mono<Void> requireActiveExperiment(UUID experimentId) {
        return experiment(experimentId).then();
    }
    public Mono<Void> requireAuthor(UUID experimentId, UUID authorId) {
        return experiment(experimentId).flatMap(experiment -> Mono.fromCallable(
                () -> client.membership(experiment.projectId(), authorId))
                .subscribeOn(Schedulers.boundedElastic())).then().onErrorMap(FeignException.class, this::translate);
    }
    private Mono<ProjectClient.ExperimentDetails> experiment(UUID id) {
        return Mono.fromCallable(() -> client.experiment(id)).subscribeOn(Schedulers.boundedElastic())
                .flatMap(experiment -> experiment.active() ? Mono.just(experiment)
                        : Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found")))
                .onErrorMap(FeignException.class, this::translate);
    }
    private RuntimeException translate(FeignException error) {
        return switch (error.status()) {
            case 404 -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project or experiment not found");
            case 422 -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "User is not a project member");
            default -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Project service is unavailable");
        };
    }
}
