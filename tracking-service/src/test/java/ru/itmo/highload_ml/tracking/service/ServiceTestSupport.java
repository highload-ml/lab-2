package ru.itmo.highload_ml.tracking.service;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
abstract class ServiceTestSupport {
    protected TransactionalOperator transactions;
    @BeforeEach void transactionManager() {
        var manager = mock(ReactiveTransactionManager.class);
        lenient().when(manager.getReactiveTransaction(any())).thenAnswer(call -> Mono.just(mock(ReactiveTransaction.class)));
        lenient().when(manager.commit(any())).thenReturn(Mono.empty());
        lenient().when(manager.rollback(any())).thenReturn(Mono.empty());
        transactions = TransactionalOperator.create(manager);
    }
}
