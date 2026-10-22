package ru.itmo.highload_ml.project.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload_ml.project.api.internal.InternalProjectController.ExperimentDetails;
import ru.itmo.highload_ml.project.api.internal.InternalProjectController.MembershipDetails;
import ru.itmo.highload_ml.project.api.internal.InternalProjectController.ProjectDetails;
import ru.itmo.highload_ml.project.exception.ExperimentNotFoundException;
import ru.itmo.highload_ml.project.exception.NotProjectMemberException;
import ru.itmo.highload_ml.project.model.ProjectMembershipId;
import ru.itmo.highload_ml.project.repository.ExperimentRepository;
import ru.itmo.highload_ml.project.repository.ProjectMembershipRepository;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class InternalProjectService {
    private final ProjectAccessService projectAccess;
    private final ExperimentRepository experiments;
    private final ProjectMembershipRepository memberships;

    public ProjectDetails project(UUID projectId) {
        projectAccess.requireExists(projectId);
        return new ProjectDetails(projectId);
    }

    public ExperimentDetails experiment(UUID experimentId) {
        var experiment = experiments.findById(experimentId)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        return new ExperimentDetails(experiment.getId(), experiment.getProjectId(), experiment.isActive());
    }

    public MembershipDetails membership(UUID projectId, UUID userId) {
        projectAccess.requireExists(projectId);
        var membership = memberships.findById(new ProjectMembershipId(projectId, userId))
                .orElseThrow(() -> new NotProjectMemberException(projectId, userId));
        return new MembershipDetails(projectId, userId, membership.getRole());
    }
}
