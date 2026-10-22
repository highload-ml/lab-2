package ru.itmo.highload_ml.tracking.model;
import java.util.UUID;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Getter
@Setter
@NoArgsConstructor
@Table("metrics")
public class Metric {
    @Id
    private UUID id;
    private UUID runId;
    private String name;
    private java.math.BigDecimal value;
    private long step;
    private Instant recordedAt;

    public Metric(UUID runId, String name, java.math.BigDecimal value, long step) {
        this.runId = runId; this.name = name; this.value = value; this.step = step;
        this.recordedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
