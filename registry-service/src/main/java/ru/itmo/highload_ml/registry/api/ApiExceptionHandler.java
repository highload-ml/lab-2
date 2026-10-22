package ru.itmo.highload_ml.registry.api;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
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

        while (error instanceof NoFallbackAvailableException && error.getCause() instanceof Exception cause) error = cause;
        HttpStatus status = switch (error) {
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case ConflictException ignored -> HttpStatus.CONFLICT;
            case BusinessRuleViolationException ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        boolean duplicate = error instanceof DataIntegrityViolationException integrity
                && integrity.getMostSpecificCause() instanceof SQLException sql && "23505".equals(sql.getSQLState());
        if (duplicate) status = HttpStatus.CONFLICT;
        ProblemDetail problem;
        if (duplicate) {
            problem = ProblemDetail.forStatusAndDetail(status, "A record with these values already exists");
        } else if (error instanceof ErrorResponse response) {
            exchange.getResponse().getHeaders().putAll(response.getHeaders());
            problem = response.getBody();
        } else if (error instanceof FeignException || error instanceof CallNotPermittedException) {
            int upstreamStatus = error instanceof FeignException remote ? remote.status() : -1;
            status = switch (upstreamStatus) {
                case 404 -> HttpStatus.NOT_FOUND;
                case 422 -> HttpStatus.UNPROCESSABLE_CONTENT;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            problem = ProblemDetail.forStatusAndDetail(status, switch (status) {
                case NOT_FOUND -> "Project, experiment or artifact not found";
                case UNPROCESSABLE_CONTENT -> "User is not a project member";
                default -> "A required service is unavailable";
            });
        } else {
            if (status == HttpStatus.INTERNAL_SERVER_ERROR) log.error("Unexpected registry error", error);
            problem = ProblemDetail.forStatusAndDetail(status, status == HttpStatus.INTERNAL_SERVER_ERROR
                    ? "An unexpected error occurred" : error.getMessage());
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
