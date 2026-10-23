package ru.itmo.highload_ml.project.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.highload_ml.project.exception.ProjectNotFoundException;
import ru.itmo.highload_ml.project.repository.ProjectRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAccessServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ProjectAccessService accessService;

    private final UUID projectId = UUID.randomUUID();

    @Test
    void passesForExistingProject() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        assertThatCode(() -> accessService.requireExists(projectId)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownProject() {
        when(projectRepository.existsById(projectId)).thenReturn(false);
        assertThatThrownBy(() -> accessService.requireExists(projectId)).isInstanceOf(ProjectNotFoundException.class);
    }
}
