package ru.itmo.highload_ml.tracking.mapper;
import org.springframework.stereotype.Component;
import ru.itmo.highload_ml.tracking.model.Metric;
import ru.itmo.highload_ml.tracking.api.dto.MetricResponse;
@Component
public class MetricMapper {
    public MetricResponse toResponse(Metric entity) {
        return new MetricResponse(entity.getId(), entity.getRunId(), entity.getName(), entity.getValue(), entity.getStep(), entity.getRecordedAt());
    }
}
