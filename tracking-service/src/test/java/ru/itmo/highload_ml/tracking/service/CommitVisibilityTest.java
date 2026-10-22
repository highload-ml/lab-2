package ru.itmo.highload_ml.tracking.service;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import ru.itmo.highload_ml.tracking.model.Run;
import ru.itmo.highload_ml.tracking.repository.RunRepository;
import ru.itmo.highload_ml.tracking.mapper.RunMapper;
import ru.itmo.highload_ml.tracking.port.ProjectAccess;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class CommitVisibilityTest {
    @Test void resultWaitsForCommitAndIsDiscardedOnCommitFailure() {
        var manager = mock(ReactiveTransactionManager.class);
        var transaction = mock(ReactiveTransaction.class);
        var committed = Sinks.<Void>empty();
        when(manager.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
        when(manager.commit(transaction)).thenReturn(committed.asMono());
        var repository = mock(RunRepository.class);
        UUID id = UUID.randomUUID();
        var run = new Run(UUID.randomUUID(), UUID.randomUUID(), "run"); run.setId(id);
        when(repository.findById(id)).thenReturn(Mono.just(run));
        when(repository.save(run)).thenReturn(Mono.just(run));
        var service = new RunService(repository, new RunMapper(), mock(ProjectAccess.class), TransactionalOperator.create(manager));
        StepVerifier.create(service.start(id)).then(() -> assertThat(committed.tryEmitError(new IllegalStateException("commit failed")))
                .isEqualTo(Sinks.EmitResult.OK)).expectErrorMessage("commit failed").verify();
    }
}
