package ru.itmo.highload_ml.registry;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.highload_ml.registry.exception.*;
import ru.itmo.highload_ml.registry.model.ModelVersion;
import ru.itmo.highload_ml.registry.repository.*;
import ru.itmo.highload_ml.registry.service.ModelVersionService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ModelVersionServiceTest {
    @Mock ModelVersionRepository versions;
    @Mock RegistryCounterRepository counter;
    ModelVersionService service;
    UUID project = UUID.randomUUID(), artifact = UUID.randomUUID();
    @BeforeEach void setup() { service = new ModelVersionService(versions, counter); }

    @Test void duplicateDoesNotAllocateNumber() {
        when(versions.existsByArtifactId(artifact)).thenReturn(true);
        assertThatThrownBy(() -> service.register(project, artifact)).isInstanceOf(ModelArtifactAlreadyRegisteredException.class);
        verifyNoInteractions(counter);
    }
    @Test void allocatesBeforeInserting() {
        when(counter.allocate(project)).thenReturn(7L);
        when(versions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.register(project, artifact);
        assertThat(result.version()).isEqualTo(7);
        var order = inOrder(counter, versions);
        order.verify(counter).allocate(project);
        order.verify(versions).saveAndFlush(any());
    }
    @Test void promotionLocksProjectBeforeTargetAndRejectsInvalidState() {
        UUID id = UUID.randomUUID();
        when(versions.findLockedByIdAndProjectId(id, project)).thenReturn(Optional.of(new ModelVersion(project, artifact, 1)));
        assertThatThrownBy(() -> service.promote(project, id)).isInstanceOf(InvalidModelVersionTransitionException.class);
        var order = inOrder(counter, versions);
        order.verify(counter).lock(project);
        order.verify(versions).findLockedByIdAndProjectId(id, project);
        verify(versions, never()).flush();
    }
}
