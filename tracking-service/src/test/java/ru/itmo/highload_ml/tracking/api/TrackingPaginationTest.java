package ru.itmo.highload_ml.tracking.api;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TrackingPaginationTest {
    @Test void hardLimitSurvivesEnvironmentOverride() {
        assertThat(new TrackingPagination(100).size(100)).isEqualTo(50);
        assertThat(new TrackingPagination(10).size(100)).isEqualTo(10);
        assertThat(new TrackingPagination(50).page(Integer.MAX_VALUE, 50).getOffset()).isEqualTo(107374182350L);
    }
    @Test void rejectsInvalidPagination() {
        assertThatThrownBy(() -> new TrackingPagination(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackingPagination(50).size(0)).hasMessageContaining("Size must be positive");
        assertThatThrownBy(() -> new TrackingPagination(50).page(-1, 20)).hasMessageContaining("Page cannot be negative");
    }
}
