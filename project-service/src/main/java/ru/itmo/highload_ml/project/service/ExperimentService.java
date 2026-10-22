package ru.itmo.highload_ml.project.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload_ml.project.api.dto.CreateExperimentRequest;
import ru.itmo.highload_ml.project.api.dto.ExperimentResponse;
import ru.itmo.highload_ml.project.exception.ExperimentNameAlreadyTakenException;
import ru.itmo.highload_ml.project.exception.ExperimentNotFoundException;
import ru.itmo.highload_ml.project.exception.ProjectNotFoundException;
import ru.itmo.highload_ml.project.exception.TagNotFoundException;
import ru.itmo.highload_ml.project.mapper.ExperimentMapper;
import ru.itmo.highload_ml.project.model.Experiment;
import ru.itmo.highload_ml.project.model.Project;
import ru.itmo.highload_ml.project.model.Tag;
import ru.itmo.highload_ml.project.repository.ExperimentRepository;
import ru.itmo.highload_ml.project.repository.ProjectRepository;
import ru.itmo.highload_ml.project.repository.TagRepository;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ExperimentService {

    private final ProjectRepository projectRepository;
    private final ExperimentRepository experimentRepository;
    private final TagRepository tagRepository;
    private final ExperimentMapper mapper;

    @Transactional
    public ExperimentResponse create(UUID projectId, CreateExperimentRequest request) {
        Project project = findProject(projectId);
        requireNameFree(projectId, request.name());
        Experiment experiment = experimentRepository.saveAndFlush(
                new Experiment(project, request.name(), request.description()));
        return mapper.toResponse(experiment);
    }

    public ExperimentResponse getById(UUID projectId, UUID experimentId) {
        requireProject(projectId);
        return mapper.toResponse(experimentRepository.findWithTagsByIdAndProject_IdAndActiveTrue(experimentId, projectId)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId)));
    }

    public Page<ExperimentResponse> findAll(UUID projectId, UUID tagId, Pageable pageable) {
        requireProject(projectId);
        Page<Experiment> page = tagId == null
                ? experimentRepository.findByProject_IdAndActiveTrue(projectId, pageable)
                : experimentRepository.findByProject_IdAndActiveTrueAndTags_Id(projectId, tagId, pageable);

        return page.map(mapper::toResponse);
    }

    @Transactional
    public ExperimentResponse update(UUID projectId, UUID experimentId, CreateExperimentRequest request) {
        findProject(projectId);
        Experiment experiment = findExperiment(projectId, experimentId);
        if (!experiment.getName().equals(request.name())) {
            requireNameFree(projectId, request.name());
            experiment.setName(request.name());
        }
        experiment.setDescription(request.description());
        experimentRepository.flush();
        return mapper.toResponse(experiment);
    }

    @Transactional
    public ExperimentResponse addTag(UUID projectId, UUID experimentId, UUID tagId) {
        findProject(projectId);
        Experiment experiment = findExperiment(projectId, experimentId);
        Tag tag = requireTag(tagId);
        experiment.addTag(tag);
        experimentRepository.flush();
        return mapper.toResponse(experiment);
    }

    @Transactional
    public ExperimentResponse removeTag(UUID projectId, UUID experimentId, UUID tagId) {
        findProject(projectId);
        Experiment experiment = findExperiment(projectId, experimentId);
        Tag tag = requireTag(tagId);
        experiment.removeTag(tag);
        experimentRepository.flush();
        return mapper.toResponse(experiment);
    }

    @Transactional
    public void delete(UUID projectId, UUID experimentId) {
        findProject(projectId);
        Experiment experiment = findExperiment(projectId, experimentId);
        experiment.deactivate();
        experimentRepository.flush();
    }

    private void requireProject(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ProjectNotFoundException(projectId);
        }
    }

    private Project findProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private Experiment findExperiment(UUID projectId, UUID experimentId) {
        return experimentRepository.findByIdAndProject_IdAndActiveTrue(experimentId, projectId)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId));
    }

    private Tag requireTag(UUID tagId) {
        return tagRepository.findById(tagId).orElseThrow(() -> new TagNotFoundException(tagId));
    }

    private void requireNameFree(UUID projectId, String name) {
        if (experimentRepository.existsByProject_IdAndName(projectId, name)) {
            throw new ExperimentNameAlreadyTakenException(projectId, name);
        }
    }
}
