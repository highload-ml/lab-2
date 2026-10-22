package ru.itmo.highload_ml.tracking.api;
import org.springframework.dao.DuplicateKeyException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import ru.itmo.highload_ml.shared.exception.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception error, ServerWebExchange exchange) {
        HttpStatus status = switch (error) {
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case ConflictException ignored -> HttpStatus.CONFLICT;
            case DuplicateKeyException ignored -> HttpStatus.CONFLICT;
            case BusinessRuleViolationException ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        ProblemDetail problem;
        if (error instanceof ErrorResponse response) {
            exchange.getResponse().getHeaders().putAll(response.getHeaders());
            problem = response.getBody();
        } else {
            if (status == HttpStatus.INTERNAL_SERVER_ERROR) log.error("Unexpected tracking error", error);
            problem = ProblemDetail.forStatusAndDetail(status, status == HttpStatus.INTERNAL_SERVER_ERROR
                    ? "An unexpected error occurred" : error instanceof DuplicateKeyException
                            ? "A record with these values already exists" : error.getMessage());
        }
        if (error instanceof WebExchangeBindException validation) {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            validation.getFieldErrors().forEach(field -> fields.computeIfAbsent(field.getField(), key -> new java.util.ArrayList<>())
                    .add(field.getDefaultMessage() == null ? "Invalid value" : field.getDefaultMessage()));
            problem.setDetail("Request validation failed");
            problem.setProperty("validationErrors", fields);
        }
        problem.setInstance(exchange.getRequest().getURI());
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatusCode.valueOf(problem.getStatus()));
        return problem;
    }
}
