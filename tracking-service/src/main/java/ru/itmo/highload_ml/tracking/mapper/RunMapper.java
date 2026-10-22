package ru.itmo.highload_ml.tracking.mapper;
import org.springframework.stereotype.Component;
import ru.itmo.highload_ml.tracking.model.Run;
import ru.itmo.highload_ml.tracking.api.dto.RunResponse;
@Component
public class RunMapper {
    public RunResponse toResponse(Run entity) {
        return new RunResponse(entity.getId(), entity.getExperimentId(), entity.getAuthorId(), entity.getName(), entity.getStatus(), entity.getCreatedAt(), entity.getStartedAt(), entity.getFinishedAt());
    }
}
