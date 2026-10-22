package ru.itmo.highload_ml.tracking.service;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import ru.itmo.highload_ml.tracking.repository.*;
import ru.itmo.highload_ml.tracking.mapper.ArtifactMapper;
import ru.itmo.highload_ml.tracking.model.*;
import ru.itmo.highload_ml.tracking.api.dto.*;
import ru.itmo.highload_ml.tracking.exception.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest extends ServiceTestSupport {
    @Mock RunRepository runs;
    @Mock ArtifactRepository artifacts;
    @Mock RunService runService;
    ArtifactService service;
    UUID id = UUID.randomUUID();
    CreateArtifactRequest request = new CreateArtifactRequest("model", ArtifactType.MODEL, "models/a.bin", 42L);
    @BeforeEach void setup() { service = new ArtifactService(runs, artifacts, new ArtifactMapper(), transactions, runService); }
    Run run(RunStatus status) { Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "run"); run.setId(id); run.setStatus(status); return run; }
    @Test void rejectsNonRunningRun() {
        when(runs.findLocked(id)).thenReturn(Mono.just(run(RunStatus.CREATED)));
        StepVerifier.create(service.register(id, request)).expectError(RunNotAcceptingArtifactsException.class).verify();
        verifyNoInteractions(artifacts);
    }
    @Test void rejectsUnknownRun() {
        when(runs.findLocked(id)).thenReturn(Mono.empty());
        StepVerifier.create(service.register(id, request)).expectError(RunNotFoundException.class).verify();
    }
    @Test void registersMetadata() {
        when(runs.findLocked(id)).thenReturn(Mono.just(run(RunStatus.RUNNING)));
        when(artifacts.save(any())).thenAnswer(call -> { Artifact a = call.getArgument(0); a.setId(UUID.randomUUID()); return Mono.just(a); });
        StepVerifier.create(service.register(id, request)).assertNext(a -> {
            assertThat(a.type()).isEqualTo(ArtifactType.MODEL); assertThat(a.path()).isEqualTo("models/a.bin");
        }).verifyComplete();
    }
    @Test void internalDetailsIncludeSourceRunState() {
        UUID artifactId = UUID.randomUUID();
        Artifact artifact = new Artifact(id, "model", ArtifactType.MODEL, "a", 1);
        when(artifacts.findById(artifactId)).thenReturn(Mono.just(artifact));
        when(runService.requireRun(id)).thenReturn(Mono.just(run(RunStatus.COMPLETED)));
        StepVerifier.create(service.details(artifactId)).assertNext(details -> {
            assertThat(details.runStatus()).isEqualTo(RunStatus.COMPLETED); assertThat(details.type()).isEqualTo(ArtifactType.MODEL);
        }).verifyComplete();
    }
    @Test void scopesReadsToRun() {
        UUID artifactId = UUID.randomUUID();
        when(runService.requireRun(id)).thenReturn(Mono.just(run(RunStatus.RUNNING)));
        when(artifacts.findByIdAndRunId(artifactId, id)).thenReturn(Mono.empty());
        StepVerifier.create(service.getById(id, artifactId)).expectError(ArtifactNotFoundException.class).verify();
    }
    @Test void listsAndCounts() {
        when(runService.requireRun(id)).thenReturn(Mono.just(run(RunStatus.RUNNING)));
        when(artifacts.countByRunId(id)).thenReturn(Mono.just(2L));
        when(artifacts.findPage(id, 1, 1L)).thenReturn(Flux.just(new Artifact(id, "model", ArtifactType.MODEL, "a", 1)));
        StepVerifier.create(service.count(id)).expectNext(2L).verifyComplete();
        StepVerifier.create(service.findAll(id, 1, 1)).expectNextCount(1).verifyComplete();
    }
}
