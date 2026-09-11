package com.bagbuddy.reviewservice;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les migrations sont ecrites pour PostgreSQL et les autres tests tournent sur H2 : c'est ici
 * qu'elles sont reellement jouees. V4 retire les doublons avant de poser sa contrainte unique --
 * une migration qui echouerait sur une base ou le double-clic a deja fait des degats bloquerait
 * le demarrage du service en production.
 *
 * Ignore sans Docker, comme TripCapacityConcurrencyTest.
 */
@EnabledIf("dockerAvailable")
class ReviewMigrationTest {

    private static PostgreSQLContainer<?> postgres;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer<>("postgres:15-alpine");
        postgres.start();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void insert(Statement sql, long id, String reviewer, long transaction) throws SQLException {
        sql.executeUpdate(("INSERT INTO review (id, transaction_id, reviewer_id, reviewee_id, rating, created_at) "
                + "VALUES (%d, %d, '%s', 'someone', 4, now())").formatted(id, transaction, reviewer));
    }

    @Test
    void theUniqueConstraintMigrationKeepsTheFirstReviewOfEachDuplicate() throws Exception {
        flyway("latest").clean();
        flyway("3").migrate();

        try (Connection db = connection(); Statement sql = db.createStatement()) {
            // Ce que deux envois simultanes laissaient derriere eux avant la contrainte.
            insert(sql, 1, "buyer", 10);
            insert(sql, 2, "buyer", 10);
            insert(sql, 3, "buyer", 10);
            insert(sql, 4, "seller", 10);
            insert(sql, 5, "buyer", 11);
        }

        flyway("latest").migrate();

        try (Connection db = connection(); Statement sql = db.createStatement()) {
            ResultSet kept = sql.executeQuery("SELECT id FROM review ORDER BY id");
            StringBuilder ids = new StringBuilder();
            while (kept.next()) {
                ids.append(kept.getLong(1)).append(' ');
            }
            assertThat(ids.toString().trim()).isEqualTo("1 4 5");

            assertThatThrownBy(() -> insert(sql, 6, "buyer", 10))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_review_reviewer_transaction");
        }
    }
}
