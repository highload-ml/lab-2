package ru.itmo.highload_ml.registry;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.highload_ml.registry.api.dto.RegisterModelVersionRequest;
import ru.itmo.highload_ml.registry.client.ProjectClient;
import ru.itmo.highload_ml.registry.client.TrackingClient;
import ru.itmo.highload_ml.registry.exception.InvalidModelArtifactException;
import ru.itmo.highload_ml.registry.service.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RegistryServiceTest {
    @Mock ProjectClient projects;
    @Mock TrackingClient tracking;
    @Mock ModelVersionService versions;
    RegistryService service;
    UUID project = UUID.randomUUID(), artifact = UUID.randomUUID(), user = UUID.randomUUID(), experiment = UUID.randomUUID();
    RegisterModelVersionRequest request = new RegisterModelVersionRequest(user, artifact);
    @BeforeEach void setup() { service = new RegistryService(projects, tracking, versions); }

    @Test void checksMembershipAndSourceBeforeDatabaseWrite() {
        when(tracking.artifact(artifact)).thenReturn(new TrackingClient.ArtifactDetails(artifact, experiment, "MODEL", "COMPLETED"));
        when(projects.experiment(experiment)).thenReturn(new ProjectClient.ExperimentDetails(experiment, project, false));
        service.register(project, request);
        var order = inOrder(projects, tracking, versions);
        order.verify(projects).membership(project, user);
        order.verify(tracking).artifact(artifact);
        order.verify(projects).experiment(experiment);
        order.verify(versions).register(project, artifact);
    }
    @Test void rejectsNonModel() {
        when(tracking.artifact(artifact)).thenReturn(new TrackingClient.ArtifactDetails(artifact, experiment, "LOG", "COMPLETED"));
        assertThatThrownBy(() -> service.register(project, request)).isInstanceOf(InvalidModelArtifactException.class).hasMessageContaining("MODEL");
        verifyNoInteractions(versions);
    }
    @Test void rejectsUnfinishedRun() {
        when(tracking.artifact(artifact)).thenReturn(new TrackingClient.ArtifactDetails(artifact, experiment, "MODEL", "RUNNING"));
        assertThatThrownBy(() -> service.register(project, request)).isInstanceOf(InvalidModelArtifactException.class).hasMessageContaining("COMPLETED");
        verifyNoInteractions(versions);
    }
    @Test void rejectsArtifactFromOtherProject() {
        when(tracking.artifact(artifact)).thenReturn(new TrackingClient.ArtifactDetails(artifact, experiment, "MODEL", "COMPLETED"));
        when(projects.experiment(experiment)).thenReturn(new ProjectClient.ExperimentDetails(experiment, UUID.randomUUID(), true));
        assertThatThrownBy(() -> service.register(project, request)).isInstanceOf(InvalidModelArtifactException.class).hasMessageContaining("another project");
        verifyNoInteractions(versions);
    }
    @Test void deniedMembershipDoesNotContactTrackingOrDatabase() {
        when(projects.membership(project, user)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.register(project, request)).hasMessage("unavailable");
        verifyNoInteractions(tracking, versions);
    }
}
