package ru.itmo.highload_ml.registry.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RegistryPagination {
    private final int maximum;

    public RegistryPagination(@Value("${app.pagination.max-page-size:50}") int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Maximum page size must be positive");
        this.maximum = Math.min(maximum, 50);
    }

    public PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size positive");
        }
        return PageRequest.of(page, Math.min(size, maximum), Sort.by("version").descending());
    }
}
