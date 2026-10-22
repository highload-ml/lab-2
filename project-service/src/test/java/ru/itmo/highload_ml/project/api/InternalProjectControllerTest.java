package ru.itmo.highload_ml.project.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.itmo.highload_ml.project.api.internal.InternalProjectController;
import ru.itmo.highload_ml.project.api.internal.InternalProjectController.*;
import ru.itmo.highload_ml.project.exception.*;
import ru.itmo.highload_ml.project.model.ProjectRole;
import ru.itmo.highload_ml.project.service.InternalProjectService;
import ru.itmo.highload_ml.shared.api.ApiExceptionHandler;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalProjectControllerTest {
    private final InternalProjectService service = mock(InternalProjectService.class);
    private MockMvc mvc;
    private final UUID projectId = UUID.randomUUID();
    private final UUID experimentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new InternalProjectController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void returnsSmallDtosAndHistoricalActivity() throws Exception {
        when(service.project(projectId)).thenReturn(new ProjectDetails(projectId));
        when(service.experiment(experimentId)).thenReturn(new ExperimentDetails(experimentId, projectId, false));
        when(service.membership(projectId, userId)).thenReturn(new MembershipDetails(projectId, userId, ProjectRole.OWNER));
        mvc.perform(get("/internal/projects/{id}", projectId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()));
        mvc.perform(get("/internal/experiments/{id}", experimentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(experimentId.toString()))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/internal/projects/{id}/members/{user}", projectId, userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test void mapsMissingResourcesAndDeniedMembership() throws Exception {
        when(service.project(projectId)).thenThrow(new ProjectNotFoundException(projectId));
        when(service.experiment(experimentId)).thenThrow(new ExperimentNotFoundException(experimentId));
        when(service.membership(projectId, userId)).thenThrow(new NotProjectMemberException(projectId, userId));
        mvc.perform(get("/internal/projects/{id}", projectId)).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/internal/experiments/{id}", experimentId)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/projects/{id}/members/{user}", projectId, userId))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/internal/projects/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
