package ru.itmo.highload_ml.tracking.api;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Component
public class TrackingPagination {
    private final int maximum;
    public TrackingPagination(@Value("${app.pagination.max-page-size:50}") int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Maximum page size must be positive");
        this.maximum = Math.min(50, maximum);
    }
    public int size(int size) {
        if (size < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size must be positive");
        return Math.min(size, maximum);
    }
    public PageRequest page(int page, int size, String... sort) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page cannot be negative");
        return PageRequest.of(page, size(size), Sort.by(sort));
    }
}
