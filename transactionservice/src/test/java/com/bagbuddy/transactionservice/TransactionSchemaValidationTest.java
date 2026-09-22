package com.bagbuddy.transactionservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Demarre le service comme en production contre un vrai PostgreSQL : Flyway joue toutes les
 * migrations, puis Hibernate valide les entites contre elles. Les autres tests tournent sur H2
 * avec create-drop, qui masquerait une entite en desaccord avec db/migration.
 *
 * Ignore sans Docker.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@EnabledIf("dockerAvailable")
class TransactionSchemaValidationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:15-alpine");
        }
    }

    @Test
    void entitiesMatchTheFlywaySchema() {
        // Le contexte a demarre : migrations jouees, entites validees.
    }
}
