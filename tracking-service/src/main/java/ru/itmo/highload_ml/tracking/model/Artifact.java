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
@Table("artifacts")
public class Artifact {
    @Id
    private UUID id;
    private UUID runId;
    private String name;
    private ArtifactType type;
    private String path;
    private long sizeBytes;
    private Instant createdAt;

    public Artifact(UUID runId, String name, ArtifactType type, String path, long sizeBytes) {
        this.runId = runId; this.name = name; this.type = type; this.path = path; this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
