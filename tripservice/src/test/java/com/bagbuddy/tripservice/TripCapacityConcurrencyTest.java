package com.bagbuddy.tripservice;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.service.TripService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regle qui protege l'inventaire -- ne jamais survendre la capacite d'une
 * annonce -- repose sur un SELECT ... FOR UPDATE (TripRepository.findByIdForUpdate,
 * PESSIMISTIC_WRITE). C'est precisement ce que la base H2 des autres tests
 * n'emule pas fidelement : ils ne prouvent donc rien sur ce point.
 *
 * Ce test lance quatre reservations simultanees de 4 kg sur une annonce qui n'en
 * a que 10, contre un vrai PostgreSQL. Sans le verrou, les quatre passeraient le
 * controle de capacite et le poids restant finirait negatif.
 *
 * Il lui faut un vrai PostgreSQL, obtenu de deux facons :
 *
 *  - par defaut, Testcontainers en demarre un (necessite Docker) ;
 *  - sinon, on lui en fournit un deja debout, ce qui est le cas courant en CI ou
 *    la base est un service du job plutot qu'un conteneur a lancer :
 *
 *      ./mvnw test -Dtest=TripCapacityConcurrencyTest \
 *        -Dbagbuddy.test.postgres=external \
 *        -Dspring.datasource.url=jdbc:postgresql://localhost:5440/tripservice_test \
 *        -Dspring.datasource.username=... -Dspring.datasource.password=...
 *
 * Sans l'un ni l'autre le test est ignore, pour que le reste de la suite continue
 * de tourner sans Docker.
 */
@SpringBootTest(properties = {
        // On rejoue les vraies migrations Flyway et on valide les entites contre
        // elles : ce test verifie aussi que db/migration decrit bien le schema.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@EnabledIf("postgresAvailable")
class TripCapacityConcurrencyTest {

    private static final String MODE = System.getProperty("bagbuddy.test.postgres", "testcontainers");

    static boolean postgresAvailable() {
        if (!"testcontainers".equals(MODE)) {
            return true; // base fournie par l'environnement
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // @TestConfiguration et non @Configuration : une @Configuration imbriquee
    // remplacerait la configuration principale de l'application, et le contexte
    // demarrerait sans aucun bean du service.
    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "bagbuddy.test.postgres", havingValue = "testcontainers",
            matchIfMissing = true)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:15-alpine");
        }
    }

    @Autowired
    private TripService tripService;

    @Autowired
    private TripRepository tripRepository;

    @Test
    void concurrentBookingsCannotOversellTheSameListing() throws Exception {
        tripRepository.deleteAll();

        Trip trip = new Trip();
        trip.setUserId("seller-sub");
        trip.setDepartureAirport("CDG");
        trip.setArrivalAirport("JFK");
        trip.setDepartureDate(LocalDateTime.now().plusDays(10));
        trip.setArrivalDate(LocalDateTime.now().plusDays(10).plusHours(8));
        trip.setTotalWeightAvailable(new BigDecimal("10"));
        trip.setRemainingWeight(new BigDecimal("10"));
        trip.setPricePerKg(new BigDecimal("12.50"));
        Long id = tripRepository.save(trip).getId();

        int attempts = 4;
        BigDecimal each = new BigDecimal("4");
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Void>> bookings = java.util.Collections.nCopies(attempts, (Callable<Void>) () -> {
                try {
                    tripService.reserveCapacity(id, each);
                    accepted.incrementAndGet();
                } catch (IllegalArgumentException expected) {
                    // "Requested weight exceeds the remaining capacity" : c'est le
                    // refus attendu pour les reservations de trop.
                    refused.incrementAndGet();
                }
                return null;
            });
            for (Future<Void> result : pool.invokeAll(bookings)) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 10 kg disponibles, 4 kg par reservation : deux passent, deux sont refusees.
        assertThat(accepted.get()).isEqualTo(2);
        assertThat(refused.get()).isEqualTo(2);

        BigDecimal remaining = tripRepository.findById(id).orElseThrow().getRemainingWeight();
        assertThat(remaining).isEqualByComparingTo("2");
        assertThat(remaining.signum()).isNotNegative();
    }
}
