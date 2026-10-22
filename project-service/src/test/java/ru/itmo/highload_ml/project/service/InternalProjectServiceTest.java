package ru.itmo.highload_ml.project.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.highload_ml.project.model.*;
import ru.itmo.highload_ml.project.repository.*;
import ru.itmo.highload_ml.project.exception.*;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.itmo.highload_ml.project.TestEntities.project;
import static ru.itmo.highload_ml.project.TestEntities.user;
import static ru.itmo.highload_ml.project.TestEntities.membership;

@ExtendWith(MockitoExtension.class)
class InternalProjectServiceTest {
    @Mock ProjectAccessService projectAccess;
    @Mock ExperimentRepository experiments;
    @Mock ProjectMembershipRepository memberships;
    @InjectMocks InternalProjectService service;

    @Test void returnsHistoricalExperimentWithInactiveFlag() {
        var project = project("history");
        var experiment = new Experiment(project, "baseline", null);
        experiment.deactivate();
        UUID id = UUID.randomUUID();
        when(experiments.findById(id)).thenReturn(Optional.of(experiment));
        assertThat(service.experiment(id).active()).isFalse();
        assertThat(service.experiment(id).projectId()).isEqualTo(project.getId());
    }
    @Test void rejectsMissingExperimentAndMembership() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.experiment(id)).isInstanceOf(ExperimentNotFoundException.class);
        assertThatThrownBy(() -> service.membership(id, id)).isInstanceOf(NotProjectMemberException.class);
        verify(projectAccess).requireExists(id);
    }
    @Test void returnsMembershipOnlyAfterCheckingProject() {
        var project = project("members");
        var user = user("alice");
        var member = membership(project, user, ProjectRole.OWNER);
        when(memberships.findById(new ProjectMembershipId(project.getId(), user.getId())))
                .thenReturn(Optional.of(member));

        var response = service.membership(project.getId(), user.getId());

        assertThat(response.projectId()).isEqualTo(project.getId());
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.role()).isEqualTo(ProjectRole.OWNER);
        verify(projectAccess).requireExists(project.getId());
    }
    @Test void checksProjectBeforeReturningId() {
        UUID id = UUID.randomUUID();
        assertThat(service.project(id).id()).isEqualTo(id);
        verify(projectAccess).requireExists(id);
    }
}
