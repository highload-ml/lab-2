package ru.itmo.highload_ml.project.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ru.itmo.highload_ml.project.ProjectModuleIntegrationTest;
import ru.itmo.highload_ml.project.api.dto.CreateExperimentRequest;
import ru.itmo.highload_ml.project.model.Project;
import ru.itmo.highload_ml.project.repository.ProjectRepository;
import ru.itmo.highload_ml.project.service.ExperimentService;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class InternalProjectIntegrationTest extends ProjectModuleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired ExperimentService experiments;

    @Test void deletionHidesPublicDataButRetainsHistoricalInternalLookup() throws Exception {
        var project = projects.saveAndFlush(new Project("history", null));
        var experiment = experiments.create(project.getId(), new CreateExperimentRequest("baseline", null));
        mvc.perform(get("/internal/experiments/{id}", experiment.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        experiments.delete(project.getId(), experiment.id());
        mvc.perform(get("/internal/experiments/{id}", experiment.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/projects/{p}/experiments/{e}", project.getId(), experiment.id()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/{p}/experiments", project.getId()))
                .andExpect(status().isOk()).andExpect(header().string("X-Total-Count", "0"))
                .andExpect(content().json("[]"));
    }
}
