package ru.itmo.highload_ml.registry.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One PostgreSQL counter row serializes version allocation and promotion in each project. */
@Repository
@RequiredArgsConstructor
public class RegistryCounterRepository {
    private final JdbcTemplate jdbc;

    public long allocate(UUID projectId) {
        return jdbc.queryForObject("""
                INSERT INTO registry_version_counters (project_id, next_version)
                VALUES (?, 2)
                ON CONFLICT (project_id) DO UPDATE
                SET next_version = registry_version_counters.next_version + 1
                RETURNING next_version - 1
                """, Long.class, projectId);
    }

    public void lock(UUID projectId) {
        // A registered version always has a counter. An empty project needs no lock.
        jdbc.queryForList("SELECT project_id FROM registry_version_counters WHERE project_id = ? FOR UPDATE",
                UUID.class, projectId);
    }
}
