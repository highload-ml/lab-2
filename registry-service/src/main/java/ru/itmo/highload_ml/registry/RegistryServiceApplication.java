package ru.itmo.highload_ml.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@EnableFeignClients
@SpringBootApplication
public class RegistryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegistryServiceApplication.class, args);
    }

    @Bean
    CircuitBreakerNameResolver dependencyCircuitNames() {

        return (clientName, target, method) -> clientName;
    }
}
