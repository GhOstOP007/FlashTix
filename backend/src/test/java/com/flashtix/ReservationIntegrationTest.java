
package com.flashtix;

import com.flashtix.dto.CancelReservationRequest;
import com.flashtix.dto.CreateReservationRequest;
import com.flashtix.dto.ReservationResponse;
import com.flashtix.service.ReservationService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=16"
})
@Testcontainers
class ReservationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbc;

    private record TestShow(
            Long showId,
            List<Long> seatIds) {
    }

    // ---------------------------------------------
    // Create fresh test data for each test
    // ---------------------------------------------

    private TestShow createTestShow(int seatCount) {

        Instant startTime = Instant.parse(
                "2028-12-15T18:30:00Z");

        Long eventId = jdbc.queryForObject("""
                INSERT INTO events (
                    title, category, start_time,
                    venue_name, city
                )
                VALUES (
                    'Reservation Integration Test',
                    'CONCERT',
                    ?,
                    'Test Arena',
                    'New Delhi'
                )
                RETURNING id
                """,
                Long.class,
                java.sql.Timestamp.from(startTime));

        Long venueId = jdbc.queryForObject("""
                INSERT INTO venues (name, city)
                VALUES ('Test Arena', 'New Delhi')
                RETURNING id
                """,
                Long.class);

        Long showId = jdbc.queryForObject("""
                INSERT INTO event_shows (
                    event_id, venue_id, start_time, status
                )
                VALUES (?, ?, ?, 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                eventId,
                venueId,
                java.sql.Timestamp.from(startTime));

        List<Long> seatIds = new ArrayList<>();

        for (int number = 1; number <= seatCount; number++) {

            Long physicalSeatId = jdbc.queryForObject("""
                    INSERT INTO seats (
                        venue_id,
                        row_label,
                        seat_number,
                        seat_type
                    )
                    VALUES (?, 'A', ?, 'REGULAR')
                    RETURNING id
                    """,
                    Long.class,
                    venueId,
                    number);

            Long inventoryId = jdbc.queryForObject("""
                    INSERT INTO show_seats (
                        show_id, venue_id, seat_id,
                        price, status
                    )
                    VALUES (?, ?, ?, 499.00, 'AVAILABLE')
                    RETURNING id
                    """,
                    Long.class,
                    showId,
                    venueId,
                    physicalSeatId);

            seatIds.add(inventoryId);
        }

        return new TestShow(showId, seatIds);
    }

    private CreateReservationRequest request(
            TestShow show,
            UUID sessionId,
            UUID idempotencyKey,
            List<Long> seatIds) {

        return new CreateReservationRequest(
                sessionId,
                idempotencyKey,
                show.showId(),
                seatIds);
    }

    // ---------------------------------------------
    // TEST 1: Create and retry
    // ---------------------------------------------

    @Test
    void retryMustReturnSameReservation() {

        TestShow show = createTestShow(3);

        UUID sessionId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        CreateReservationRequest input = request(
                show,
                sessionId,
                key,
                show.seatIds().subList(0, 2));

        ReservationResponse first = reservationService.createReservation(input);

        ReservationResponse retry = reservationService.createReservation(input);

        assertEquals(
                first.reservationId(),
                retry.reservationId());

        assertEquals("HELD", first.status());
        assertEquals(2, first.showSeatIds().size());

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM reservations
                WHERE client_session_id = ?
                  AND idempotency_key = ?
                """,
                Integer.class,
                sessionId,
                key);

        assertEquals(1, count);

        System.out.println(
                "PASS: Identical retries return the same reservation");
    }

    // ---------------------------------------------
    // TEST 2: Same key, different seats
    // ---------------------------------------------

    @Test
    void reusedKeyWithDifferentSeatsMustFail() {

        TestShow show = createTestShow(3);

        UUID sessionId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        reservationService.createReservation(
                request(
                        show,
                        sessionId,
                        key,
                        List.of(show.seatIds().get(0))));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> reservationService.createReservation(
                        request(
                                show,
                                sessionId,
                                key,
                                List.of(show.seatIds().get(1)))));

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode());

        System.out.println(
                "PASS: Conflicting idempotency key rejected");
    }

    // ---------------------------------------------
    // TEST 3: Cancel all reserved seats
    // ---------------------------------------------

    @Test
    void cancellationMustReleaseAllSeats() {

        TestShow show = createTestShow(3);

        UUID sessionId = UUID.randomUUID();

        ReservationResponse reservation = reservationService.createReservation(
                request(
                        show,
                        sessionId,
                        UUID.randomUUID(),
                        show.seatIds()));

        ReservationResponse cancelled = reservationService.cancelReservation(
                reservation.reservationId(),
                new CancelReservationRequest(sessionId));

        assertEquals("CANCELLED", cancelled.status());

        Integer available = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM show_seats
                WHERE show_id = ?
                  AND status = 'AVAILABLE'
                  AND hold_token IS NULL
                """,
                Integer.class,
                show.showId());

        assertEquals(3, available);

        // Historical reservation records must remain.
        Integer historyRows = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM reservation_seats
                WHERE reservation_id = ?
                """,
                Integer.class,
                reservation.reservationId());

        assertEquals(3, historyRows);

        ResponseStatusException secondCancel = assertThrows(
                ResponseStatusException.class,
                () -> reservationService.cancelReservation(
                        reservation.reservationId(),
                        new CancelReservationRequest(sessionId)));

        assertEquals(
                HttpStatus.CONFLICT,
                secondCancel.getStatusCode());

        System.out.println(
                "PASS: Group cancellation and history preservation");
    }

    // ---------------------------------------------
    // TEST 4: Reservation ownership
    // ---------------------------------------------

    @Test
    void anotherSessionCannotReadOrCancelReservation() {

        TestShow show = createTestShow(2);

        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        ReservationResponse reservation = reservationService.createReservation(
                request(
                        show,
                        owner,
                        UUID.randomUUID(),
                        List.of(show.seatIds().get(0))));

        ResponseStatusException readError = assertThrows(
                ResponseStatusException.class,
                () -> reservationService.getReservation(
                        reservation.reservationId(),
                        stranger));

        assertEquals(
                HttpStatus.NOT_FOUND,
                readError.getStatusCode());

        ResponseStatusException cancelError = assertThrows(
                ResponseStatusException.class,
                () -> reservationService.cancelReservation(
                        reservation.reservationId(),
                        new CancelReservationRequest(stranger)));

        assertEquals(
                HttpStatus.NOT_FOUND,
                cancelError.getStatusCode());

        // The legitimate owner still has an active hold.
        ReservationResponse original = reservationService.getReservation(
                reservation.reservationId(),
                owner);

        assertEquals("HELD", original.status());

        System.out.println(
                "PASS: Different session cannot read or cancel");
    }

    // ---------------------------------------------
    // TEST 5: Concurrent identical submissions
    // ---------------------------------------------

    @Test
    void concurrentRetriesMustCreateOnlyOneReservation()
            throws Exception {

        TestShow show = createTestShow(2);

        UUID sessionId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        CreateReservationRequest input = request(
                show,
                sessionId,
                key,
                show.seatIds());

        int threadCount = 12;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(threadCount);

        CountDownLatch start = new CountDownLatch(1);

        List<Future<UUID>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {

                futures.add(executor.submit(() -> {

                    ready.countDown();
                    start.await();

                    ReservationResponse response = reservationService.createReservation(
                            input);

                    return response.reservationId();
                }));
            }

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "Workers did not become ready");

            long started = System.nanoTime();

            start.countDown();

            List<UUID> returnedIds = new ArrayList<>();

            for (Future<UUID> future : futures) {
                returnedIds.add(
                        future.get(60, TimeUnit.SECONDS));
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);

            // All requests must return the same ID.
            long distinctIds = returnedIds.stream()
                    .distinct()
                    .count();

            assertEquals(1L, distinctIds);

            // Exactly one reservation record exists.
            Integer reservationCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM reservations
                    WHERE client_session_id = ?
                      AND idempotency_key = ?
                    """,
                    Integer.class,
                    sessionId,
                    key);

            assertEquals(1, reservationCount);

            // Exactly two seats belong to that reservation.
            UUID reservationId = returnedIds.get(0);

            Integer linkedSeats = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM reservation_seats
                    WHERE reservation_id = ?
                    """,
                    Integer.class,
                    reservationId);

            assertEquals(2, linkedSeats);

            System.out.println(
                    "Concurrent identical requests: " + threadCount);
            System.out.println(
                    "Distinct reservations: " + distinctIds);
            System.out.println(
                    "Elapsed: " + elapsedMs + " ms");

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
