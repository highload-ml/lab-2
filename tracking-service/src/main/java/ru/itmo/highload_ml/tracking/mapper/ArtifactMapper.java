package ru.itmo.highload_ml.tracking.mapper;
import org.springframework.stereotype.Component;
import ru.itmo.highload_ml.tracking.model.Artifact;
import ru.itmo.highload_ml.tracking.api.dto.ArtifactResponse;
@Component
public class ArtifactMapper {
    public ArtifactResponse toResponse(Artifact entity) {
        return new ArtifactResponse(entity.getId(), entity.getRunId(), entity.getName(), entity.getType(), entity.getPath(), entity.getSizeBytes(), entity.getCreatedAt());
    }
}
