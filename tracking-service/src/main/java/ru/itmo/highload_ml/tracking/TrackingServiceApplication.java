package ru.itmo.highload_ml.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@EnableFeignClients
@SpringBootApplication
public class TrackingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrackingServiceApplication.class, args);
    }

    @Bean
    CircuitBreakerNameResolver dependencyCircuitNames() {
        // One breaker per dependency in this application, shared by that client's methods.
        return (clientName, target, method) -> clientName;
    }
}
