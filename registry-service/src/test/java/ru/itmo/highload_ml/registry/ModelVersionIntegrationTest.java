package ru.itmo.highload_ml.registry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.itmo.highload_ml.registry.api.dto.ModelVersionResponse;
import ru.itmo.highload_ml.registry.exception.*;
import ru.itmo.highload_ml.registry.model.ModelVersionState;
import ru.itmo.highload_ml.registry.service.ModelVersionService;
import static org.assertj.core.api.Assertions.*;

class ModelVersionIntegrationTest extends BaseIntegrationTest {
    @Autowired ModelVersionService service;
    @Autowired JdbcTemplate jdbc;
    UUID project = UUID.randomUUID();

    @BeforeEach
    void clean() { jdbc.execute("TRUNCATE model_versions, registry_version_counters"); }

    ModelVersionResponse register() { return service.register(project, UUID.randomUUID()); }

    @Test
    void registersNumbersListsAndScopesReadsToProject() {
        var first = register();
        var second = register();
        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(service.getById(project, first.id())).isEqualTo(first);
        assertThat(service.findAll(project, PageRequest.of(0, 1, Sort.by("version").descending())).getContent()).containsExactly(second);
        assertThat(service.findAll(project, PageRequest.of(0, 1)).getTotalElements()).isEqualTo(2);
        assertThatThrownBy(() -> service.getById(UUID.randomUUID(), first.id())).isInstanceOf(ModelVersionNotFoundException.class);
        assertThatThrownBy(() -> service.getProduction(project)).isInstanceOf(ModelVersionNotFoundException.class);
        assertThatThrownBy(() -> service.stage(project, UUID.randomUUID())).isInstanceOf(ModelVersionNotFoundException.class);
        assertThatThrownBy(() -> service.promote(UUID.randomUUID(), first.id())).isInstanceOf(ModelVersionNotFoundException.class);
        assertThatThrownBy(() -> service.register(project, first.artifactId())).isInstanceOf(ModelArtifactAlreadyRegisteredException.class);
        assertThat(register().version()).isEqualTo(3);
    }

    @Test
    void promotesAndArchivesAtomically() {
        var first = register();
        var second = register();
        assertThatThrownBy(() -> service.promote(project, first.id())).isInstanceOf(InvalidModelVersionTransitionException.class);
        service.stage(project, first.id());
        assertThatThrownBy(() -> service.stage(project, first.id())).isInstanceOf(InvalidModelVersionTransitionException.class);
        var old = service.promote(project, first.id());
        service.stage(project, second.id());
        var current = service.promote(project, second.id());
        assertThat(service.getProduction(project)).isEqualTo(current);
        assertThat(service.getById(project, first.id()).state()).isEqualTo(ModelVersionState.ARCHIVED);
        assertThat(service.getById(project, first.id()).promotedAt()).isEqualTo(old.promotedAt());
    }

    @Test
    void failedInsertRollsBackCounterIncrement() {
        register();
        jdbc.execute("ALTER TABLE model_versions ADD CONSTRAINT test_reject_registration CHECK (version < 2)");
        try { assertThatThrownBy(this::register).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE model_versions DROP CONSTRAINT test_reject_registration"); }
        assertThat(register().version()).isEqualTo(2);
    }

    @Test
    void failureAfterArchivingRestoresPreviousProduction() {
        var first = register();
        var second = register();
        service.stage(project, first.id());
        var old = service.promote(project, first.id());
        service.stage(project, second.id());
        jdbc.execute("ALTER TABLE model_versions ADD CONSTRAINT test_reject_promotion CHECK (version <> 2 OR state <> 'PRODUCTION')");
        try { assertThatThrownBy(() -> service.promote(project, second.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE model_versions DROP CONSTRAINT test_reject_promotion"); }
        assertThat(service.getProduction(project)).isEqualTo(old);
        assertThat(service.getById(project, second.id()).state()).isEqualTo(ModelVersionState.STAGING);
    }

    @Test
    void concurrentRegistrationsAllocateDifferentNumbers() throws Exception {
        var values = together(this::register, this::register);
        assertThat(values).extracting(ModelVersionResponse::version).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void concurrentDuplicateArtifactHasOneWinnerAndDoesNotConsumeNumber() throws Exception {
        UUID artifact = UUID.randomUUID();
        Callable<Boolean> attempt = () -> {
            try { service.register(project, artifact); return true; }
            catch (ModelArtifactAlreadyRegisteredException expected) { return false; }
            catch (org.springframework.dao.DataIntegrityViolationException conflict) {
                var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                        org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/model-versions").build());
                assertThat(new ru.itmo.highload_ml.registry.api.ApiExceptionHandler().handle(conflict, exchange).getStatus())
                        .isEqualTo(409);
                return false;
            }
        };
        assertThat(together(attempt, attempt)).containsExactlyInAnyOrder(true, false);
        assertThat(register().version()).isEqualTo(2);
    }

    @Test
    void concurrentPromotionsLeaveOneProductionEvenWhenInitiallyEmpty() throws Exception {
        var first = register();
        var second = register();
        service.stage(project, first.id());
        service.stage(project, second.id());
        together(() -> service.promote(project, first.id()), () -> service.promote(project, second.id()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_versions WHERE state = 'PRODUCTION'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_versions WHERE state = 'ARCHIVED'", Long.class)).isEqualTo(1);
    }

    @Test
    void schemaStoresStringStatesAndRejectsSecondProduction() {
        var first = register();
        var second = register();
        service.stage(project, first.id());
        service.promote(project, first.id());
        assertThat(jdbc.queryForObject("SELECT state FROM model_versions WHERE id = ?", String.class, first.id())).isEqualTo("PRODUCTION");
        assertThatThrownBy(() -> jdbc.update("UPDATE model_versions SET state = 'PRODUCTION', promoted_at = now() WHERE id = ?", second.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
}
