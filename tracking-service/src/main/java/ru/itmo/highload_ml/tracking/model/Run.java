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
@Table("runs")
public class Run {
    @Id
    private UUID id;
    private UUID experimentId;
    private UUID authorId;
    private String name;
    private RunStatus status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    public Run(UUID experimentId, UUID authorId, String name) {
        this.experimentId = experimentId; this.authorId = authorId; this.name = name;
        this.status = RunStatus.CREATED; this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
