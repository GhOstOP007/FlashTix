
package com.flashtix;

import com.flashtix.dto.AuthenticatedReservationRequest;
import com.flashtix.dto.ReservationResponse;
import com.flashtix.service.AuthenticatedReservationService;
import com.flashtix.service.ReservationService;
import com.flashtix.service.SeatHoldService;

import org.junit.jupiter.api.Test;

import com.flashtix.service.ReservationExpiryJob;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.flashtix.service.MockPaymentService;
import com.flashtix.service.PaymentFulfillmentEngine;
import com.flashtix.dto.PaymentOrderResponse;
import com.flashtix.dto.SimulatePaymentRequest;

import com.flashtix.service.ReservationExpiryJob;

import com.flashtix.service.PaymentFulfillmentEngine;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
                "spring.datasource.hikari.maximum-pool-size=16",
                "flashtix.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Testcontainers
class AuthenticatedReservationIntegrationTest {

        @Container
        @ServiceConnection
        static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

        @Autowired
        private PaymentFulfillmentEngine paymentFulfillmentEngine;
        @Autowired
        private MockPaymentService mockPaymentService;

        @Autowired
        private ReservationExpiryJob reservationExpiryJob;

        @Autowired
        private AuthenticatedReservationService authenticatedService;

        @Autowired
        private ReservationService reservationService;

        @Autowired
        private SeatHoldService seatHoldService;

        @Autowired
        private JdbcTemplate jdbc;

        private record TestShow(
                        Long showId,
                        List<Long> seatIds) {
        }

        // ----------------------------------------------
        // TEST FIXTURES
        // ----------------------------------------------

        private UUID createUser(boolean enabled) {

                UUID userId = UUID.randomUUID();

                String email = "test-" + userId + "@flashtix.test";

                jdbc.update("""
                                INSERT INTO users (
                                    id,
                                    full_name,
                                    email,
                                    password_hash,
                                    role,
                                    enabled
                                )
                                VALUES (?, ?, ?, ?, 'USER', ?)
                                """,
                                userId,
                                "Integration Test User",
                                email,
                                "test-only-hash-not-for-login",
                                enabled);

                return userId;
        }

        private TestShow createShow(int count) {

                Instant startTime = Instant.now()
                                .plus(30, ChronoUnit.DAYS);

                Long eventId = jdbc.queryForObject("""
                                INSERT INTO events (
                                    title,
                                    category,
                                    start_time,
                                    venue_name,
                                    city
                                )
                                VALUES (
                                    'Authenticated Reservation Test',
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
                                INSERT INTO venues (
                                    name,
                                    city
                                )
                                VALUES (
                                    'Integration Arena',
                                    'New Delhi'
                                )
                                RETURNING id
                                """,
                                Long.class);

                Long showId = jdbc.queryForObject("""
                                INSERT INTO event_shows (
                                    event_id,
                                    venue_id,
                                    start_time,
                                    status
                                )
                                VALUES (?, ?, ?, 'SCHEDULED')
                                RETURNING id
                                """,
                                Long.class,
                                eventId,
                                venueId,
                                java.sql.Timestamp.from(startTime));

                List<Long> seatIds = new ArrayList<>();

                for (int n = 1; n <= count; n++) {

                        Long seatId = jdbc.queryForObject("""
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
                                        n);

                        Long showSeatId = jdbc.queryForObject("""
                                        INSERT INTO show_seats (
                                            show_id,
                                            venue_id,
                                            seat_id,
                                            price,
                                            status
                                        )
                                        VALUES (
                                            ?, ?, ?, 499.00, 'AVAILABLE'
                                        )
                                        RETURNING id
                                        """,
                                        Long.class,
                                        showId,
                                        venueId,
                                        seatId);

                        seatIds.add(showSeatId);
                }

                return new TestShow(showId, seatIds);
        }

        private AuthenticatedReservationRequest request(
                        TestShow show,
                        UUID key,
                        List<Long> seatIds) {
                return new AuthenticatedReservationRequest(
                                key,
                                show.showId(),
                                seatIds);
        }

        private int countReservations(
                        UUID userId,
                        UUID idempotencyKey) {

                Integer count = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM reservations
                                WHERE user_id = ?
                                  AND idempotency_key = ?
                                """,
                                Integer.class,
                                userId,
                                idempotencyKey);

                return count == null ? 0 : count;
        }

        // ----------------------------------------------
        // TEST 1 — AUTHENTICATED CREATION
        // ----------------------------------------------

        @Test
        void authenticatedUserCanReserveSeats() {

                UUID userId = createUser(true);
                TestShow show = createShow(3);
                UUID key = UUID.randomUUID();

                ReservationResponse result = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                key,
                                                show.seatIds().subList(0, 2)));

                assertNotNull(result.reservationId());
                assertEquals("HELD", result.status());
                assertEquals(2, result.showSeatIds().size());

                assertEquals(
                                0,
                                result.totalPrice().compareTo(
                                                new BigDecimal("998.00")));

                assertEquals(
                                1,
                                countReservations(userId, key));

                System.out.println(
                                "PASS: Authenticated reservation created");
        }

        // ----------------------------------------------
        // TEST 2 — DIFFERENT USER CANNOT ACCESS
        // ----------------------------------------------

        @Test
        void anotherUserCannotReadOrCancelReservation() {

                UUID owner = createUser(true);
                UUID stranger = createUser(true);

                TestShow show = createShow(2);

                ReservationResponse reservation = authenticatedService.createReservation(
                                owner,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                List.of(show.seatIds().get(0))));

                ResponseStatusException readError = assertThrows(
                                ResponseStatusException.class,
                                () -> authenticatedService.getReservation(
                                                stranger,
                                                reservation.reservationId()));

                assertEquals(
                                HttpStatus.NOT_FOUND,
                                readError.getStatusCode());

                ResponseStatusException cancelError = assertThrows(
                                ResponseStatusException.class,
                                () -> authenticatedService.cancelReservation(
                                                stranger,
                                                reservation.reservationId()));

                assertEquals(
                                HttpStatus.NOT_FOUND,
                                cancelError.getStatusCode());

                ReservationResponse original = authenticatedService.getReservation(
                                owner,
                                reservation.reservationId());

                assertEquals("HELD", original.status());

                System.out.println(
                                "PASS: Cross-user access and cancellation rejected");
        }

        // ----------------------------------------------
        // TEST 3 — DISABLED ACCOUNT
        // ----------------------------------------------

        @Test
        void disabledUserCannotCreateReservation() {

                UUID disabledUser = createUser(false);
                TestShow show = createShow(1);

                ResponseStatusException error = assertThrows(
                                ResponseStatusException.class,
                                () -> authenticatedService.createReservation(
                                                disabledUser,
                                                request(
                                                                show,
                                                                UUID.randomUUID(),
                                                                show.seatIds())));

                assertEquals(
                                HttpStatus.FORBIDDEN,
                                error.getStatusCode());

                Integer heldCount = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM show_seats
                                WHERE show_id = ?
                                  AND status = 'HELD'
                                """,
                                Integer.class,
                                show.showId());

                assertEquals(0, heldCount);

                System.out.println(
                                "PASS: Disabled account cannot reserve seats");
        }

        // ----------------------------------------------
        // TEST 4 — CONCURRENT IDENTICAL RETRIES
        // ----------------------------------------------

        @Test
        void twelveConcurrentRetriesCreateOneReservation()
                        throws Exception {

                UUID userId = createUser(true);
                TestShow show = createShow(2);
                UUID key = UUID.randomUUID();

                AuthenticatedReservationRequest input = request(show, key, show.seatIds());

                int workers = 12;

                ExecutorService executor = Executors.newFixedThreadPool(workers);

                CountDownLatch ready = new CountDownLatch(workers);

                CountDownLatch start = new CountDownLatch(1);

                List<Future<UUID>> futures = new ArrayList<>();

                try {
                        for (int i = 0; i < workers; i++) {

                                futures.add(executor.submit(() -> {

                                        ready.countDown();
                                        start.await();

                                        ReservationResponse response = authenticatedService.createReservation(
                                                        userId,
                                                        input);

                                        return response.reservationId();
                                }));
                        }

                        assertTrue(
                                        ready.await(20, TimeUnit.SECONDS),
                                        "Not all workers started");

                        start.countDown();

                        List<UUID> results = new ArrayList<>();

                        for (Future<UUID> future : futures) {
                                results.add(
                                                future.get(60, TimeUnit.SECONDS));
                        }

                        long distinctIds = results.stream().distinct().count();

                        assertEquals(1L, distinctIds);

                        assertEquals(
                                        1,
                                        countReservations(userId, key));

                        System.out.println(
                                        "PASS: 12 requests returned one reservation ID");

                } finally {
                        start.countDown();
                        executor.shutdownNow();
                }
        }

        // ----------------------------------------------
        // TEST 5 — TWO USERS COMPETE FOR ONE SEAT
        // ----------------------------------------------

        @Test
        void onlyOneUserCanReserveSameSeat()
                        throws Exception {

                UUID userA = createUser(true);
                UUID userB = createUser(true);

                TestShow show = createShow(1);

                Long seatId = show.seatIds().get(0);

                ExecutorService executor = Executors.newFixedThreadPool(2);

                CountDownLatch ready = new CountDownLatch(2);

                CountDownLatch start = new CountDownLatch(1);

                try {

                        Callable<Boolean> userATask = () -> {
                                ready.countDown();
                                start.await();

                                try {
                                        authenticatedService.createReservation(
                                                        userA,
                                                        request(
                                                                        show,
                                                                        UUID.randomUUID(),
                                                                        List.of(seatId)));

                                        return true;

                                } catch (ResponseStatusException ex) {
                                        assertEquals(
                                                        HttpStatus.CONFLICT,
                                                        ex.getStatusCode());
                                        return false;
                                }
                        };

                        Callable<Boolean> userBTask = () -> {
                                ready.countDown();
                                start.await();

                                try {
                                        authenticatedService.createReservation(
                                                        userB,
                                                        request(
                                                                        show,
                                                                        UUID.randomUUID(),
                                                                        List.of(seatId)));

                                        return true;

                                } catch (ResponseStatusException ex) {
                                        assertEquals(
                                                        HttpStatus.CONFLICT,
                                                        ex.getStatusCode());
                                        return false;
                                }
                        };

                        Future<Boolean> resultA = executor.submit(userATask);

                        Future<Boolean> resultB = executor.submit(userBTask);

                        assertTrue(
                                        ready.await(15, TimeUnit.SECONDS));

                        start.countDown();

                        int successes = (resultA.get(60, TimeUnit.SECONDS) ? 1 : 0)
                                        + (resultB.get(60, TimeUnit.SECONDS) ? 1 : 0);

                        assertEquals(
                                        1,
                                        successes,
                                        "Exactly one user must acquire the seat");

                        Integer heldSeats = jdbc.queryForObject("""
                                        SELECT COUNT(*)
                                        FROM show_seats
                                        WHERE id = ?
                                          AND status = 'HELD'
                                        """,
                                        Integer.class,
                                        seatId);

                        assertEquals(1, heldSeats);

                        System.out.println(
                                        "PASS: Exactly one of two users reserved the seat");

                } finally {
                        start.countDown();
                        executor.shutdownNow();
                }
        }

        // ----------------------------------------------
        // TEST 6 — EXPIRED RESERVATION
        // ----------------------------------------------

        @Test
        void expiredReservationCannotBeCancelled()
                        throws Exception {

                UUID userId = createUser(true);
                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                // Simulate an expired hold without waiting
                // for five real minutes.
                jdbc.update("""
                                UPDATE reservations
                                SET expires_at = CURRENT_TIMESTAMP
                                    - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                reservation.reservationId());

                jdbc.update("""
                                UPDATE show_seats
                                SET hold_expires_at = CURRENT_TIMESTAMP
                                    - INTERVAL '1 minute'
                                WHERE hold_token = ?
                                  AND status = 'HELD'
                                """,
                                reservation.reservationId());

                reservationService.expireReservations();
                seatHoldService.cleanupExpiredHolds();

                ReservationResponse expired = authenticatedService.getReservation(
                                userId,
                                reservation.reservationId());

                assertEquals("EXPIRED", expired.status());

                ResponseStatusException error = assertThrows(
                                ResponseStatusException.class,
                                () -> authenticatedService.cancelReservation(
                                                userId,
                                                reservation.reservationId()));

                assertEquals(
                                HttpStatus.CONFLICT,
                                error.getStatusCode());

                Integer available = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM show_seats
                                WHERE id = ?
                                  AND status = 'AVAILABLE'
                                  AND hold_token IS NULL
                                """,
                                Integer.class,
                                show.seatIds().get(0));

                assertEquals(1, available);

                System.out.println(
                                "PASS: Expired reservation cannot be cancelled");
        }

        @Test
        void reservationAndSeatMustHaveSameExpiry() {

                UUID userId = createUser(true);
                TestShow show = createShow(3);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                Integer matching = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM reservation_seats rs
                                JOIN reservations r
                                  ON r.id = rs.reservation_id
                                JOIN show_seats ss
                                  ON ss.id = rs.show_seat_id
                                WHERE r.id = ?
                                  AND ss.hold_expires_at = r.expires_at
                                """,
                                Integer.class,
                                reservation.reservationId());

                assertEquals(3, matching);

                System.out.println(
                                "PASS: All seats share reservation expiry");
        }

        @Test
        void unifiedExpiryMustReleaseReservationSeats() {

                UUID userId = createUser(true);
                TestShow show = createShow(2);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                // Simulate time passing without waiting
                // for the real five-minute deadline.
                jdbc.update("""
                                UPDATE reservations
                                SET expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                reservation.reservationId());

                jdbc.update("""
                                UPDATE show_seats
                                SET hold_expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE hold_token = ?
                                """,
                                reservation.reservationId());

                // The new coordinator performs BOTH actions.
                reservationExpiryJob.runCleanup();

                String status = jdbc.queryForObject("""
                                SELECT status
                                FROM reservations
                                WHERE id = ?
                                """,
                                String.class,
                                reservation.reservationId());

                assertEquals("EXPIRED", status);

                Integer availableSeats = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM show_seats
                                WHERE show_id = ?
                                  AND status = 'AVAILABLE'
                                  AND hold_token IS NULL
                                  AND hold_expires_at IS NULL
                                """,
                                Integer.class,
                                show.showId());

                assertEquals(2, availableSeats);

                System.out.println(
                                "PASS: Unified cleanup expires reservation and releases seats");
        }

        @Test
        void unifiedCleanupMustReleaseExpiredLegacyHolds() {

                TestShow show = createShow(1);

                Long seatId = show.seatIds().get(0);
                UUID legacyToken = UUID.randomUUID();

                // An old-style hold without a reservation row.
                jdbc.update("""
                                UPDATE show_seats
                                SET status = 'HELD',
                                    hold_token = ?,
                                    hold_expires_at =
                                        CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                legacyToken,
                                seatId);

                reservationExpiryJob.runCleanup();

                String status = jdbc.queryForObject("""
                                SELECT status
                                FROM show_seats
                                WHERE id = ?
                                """,
                                String.class,
                                seatId);

                assertEquals("AVAILABLE", status);

                System.out.println(
                                "PASS: Expired standalone legacy hold released");
        }

        @Test
        void simultaneousCancellationMustReleaseSeatsOnlyOnce()
                        throws Exception {

                UUID userId = createUser(true);
                TestShow show = createShow(3);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                ExecutorService executor = Executors.newFixedThreadPool(2);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                AtomicInteger successes = new AtomicInteger();
                AtomicInteger conflicts = new AtomicInteger();

                try {
                        List<Future<?>> futures = new ArrayList<>();

                        for (int i = 0; i < 2; i++) {

                                futures.add(executor.submit(() -> {
                                        ready.countDown();
                                        start.await();

                                        try {
                                                ReservationResponse response = authenticatedService.cancelReservation(
                                                                userId,
                                                                reservation.reservationId());

                                                assertEquals(
                                                                "CANCELLED",
                                                                response.status());

                                                successes.incrementAndGet();

                                        } catch (ResponseStatusException ex) {

                                                assertEquals(
                                                                HttpStatus.CONFLICT,
                                                                ex.getStatusCode());

                                                conflicts.incrementAndGet();
                                        }

                                        return null;
                                }));
                        }

                        assertTrue(
                                        ready.await(15, TimeUnit.SECONDS));

                        start.countDown();

                        for (Future<?> future : futures) {
                                future.get(30, TimeUnit.SECONDS);
                        }

                        assertEquals(1, successes.get());
                        assertEquals(1, conflicts.get());

                        String status = jdbc.queryForObject("""
                                        SELECT status
                                        FROM reservations
                                        WHERE id = ?
                                        """,
                                        String.class,
                                        reservation.reservationId());

                        assertEquals("CANCELLED", status);

                        Integer available = jdbc.queryForObject("""
                                        SELECT COUNT(*)
                                        FROM show_seats
                                        WHERE show_id = ?
                                          AND status = 'AVAILABLE'
                                          AND hold_token IS NULL
                                          AND hold_expires_at IS NULL
                                        """,
                                        Integer.class,
                                        show.showId());

                        assertEquals(3, available);

                        System.out.println(
                                        "PASS: Two cancellations -> one success, one conflict");

                } finally {
                        start.countDown();
                        executor.shutdownNow();
                }
        }

        @Test
        void concurrentExpiryWorkersMustReleaseSeatsSafely()
                        throws Exception {

                UUID userId = createUser(true);
                TestShow show = createShow(2);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                // Artificially expire the reservation.
                jdbc.update("""
                                UPDATE reservations
                                SET expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                reservation.reservationId());

                jdbc.update("""
                                UPDATE show_seats
                                SET hold_expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE hold_token = ?
                                """,
                                reservation.reservationId());

                ExecutorService executor = Executors.newFixedThreadPool(2);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                try {
                        List<Future<?>> futures = new ArrayList<>();

                        for (int i = 0; i < 2; i++) {

                                futures.add(executor.submit(() -> {
                                        ready.countDown();
                                        start.await();

                                        reservationExpiryJob.runCleanup();

                                        return null;
                                }));
                        }

                        assertTrue(
                                        ready.await(15, TimeUnit.SECONDS));

                        start.countDown();

                        for (Future<?> future : futures) {
                                future.get(30, TimeUnit.SECONDS);
                        }

                        String status = jdbc.queryForObject("""
                                        SELECT status
                                        FROM reservations
                                        WHERE id = ?
                                        """,
                                        String.class,
                                        reservation.reservationId());

                        assertEquals("EXPIRED", status);

                        Integer available = jdbc.queryForObject("""
                                        SELECT COUNT(*)
                                        FROM show_seats
                                        WHERE show_id = ?
                                          AND status = 'AVAILABLE'
                                          AND hold_token IS NULL
                                          AND hold_expires_at IS NULL
                                        """,
                                        Integer.class,
                                        show.showId());

                        assertEquals(2, available);

                        Integer historicalLinks = jdbc.queryForObject("""
                                        SELECT COUNT(*)
                                        FROM reservation_seats
                                        WHERE reservation_id = ?
                                        """,
                                        Integer.class,
                                        reservation.reservationId());

                        assertEquals(2, historicalLinks);

                        System.out.println(
                                        "PASS: Concurrent expiration preserved reservation history");

                } finally {
                        start.countDown();
                        executor.shutdownNow();
                }
        }

        @Test
        void cleanupMustNotChangeCancelledReservation() {

                UUID userId = createUser(true);
                TestShow show = createShow(2);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                authenticatedService.cancelReservation(
                                userId,
                                reservation.reservationId());

                // Even when the old deadline has passed,
                // CANCELLED must remain a terminal state.
                jdbc.update("""
                                UPDATE reservations
                                SET expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                reservation.reservationId());

                reservationExpiryJob.runCleanup();

                String status = jdbc.queryForObject("""
                                SELECT status
                                FROM reservations
                                WHERE id = ?
                                """,
                                String.class,
                                reservation.reservationId());

                assertEquals("CANCELLED", status);

                Integer available = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM show_seats
                                WHERE show_id = ?
                                  AND status = 'AVAILABLE'
                                  AND hold_token IS NULL
                                """,
                                Integer.class,
                                show.showId());

                assertEquals(2, available);

                System.out.println(
                                "PASS: Cleanup preserves CANCELLED state");
        }

        @Test
        void cleanupMustNotReleaseAnotherUsersNewHold() {

                UUID userA = createUser(true);
                UUID userB = createUser(true);

                TestShow show = createShow(2);
                Long targetSeat = show.seatIds().get(0);

                ReservationResponse firstReservation = authenticatedService.createReservation(
                                userA,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                List.of(targetSeat)));

                authenticatedService.cancelReservation(
                                userA,
                                firstReservation.reservationId());

                ReservationResponse secondReservation = authenticatedService.createReservation(
                                userB,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                List.of(targetSeat)));

                // Expire the old, already-cancelled reservation's
                // historical deadline. The new hold stays live.
                jdbc.update("""
                                UPDATE reservations
                                SET expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                firstReservation.reservationId());

                reservationExpiryJob.runCleanup();

                UUID actualToken = jdbc.queryForObject("""
                                SELECT hold_token
                                FROM show_seats
                                WHERE id = ?
                                """,
                                UUID.class,
                                targetSeat);

                String actualStatus = jdbc.queryForObject("""
                                SELECT status
                                FROM show_seats
                                WHERE id = ?
                                """,
                                String.class,
                                targetSeat);

                assertEquals(
                                secondReservation.reservationId(),
                                actualToken);

                assertEquals("HELD", actualStatus);

                System.out.println(
                                "PASS: Old reservation cleanup preserves new owner's seat");
        }

        @Test
        void repeatedPaymentOrderCreationReturnsSameOrder() {

                UUID userId = createUser(true);
                TestShow show = createShow(2);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                PaymentOrderResponse first = mockPaymentService.createOrder(
                                userId,
                                reservation.reservationId());

                PaymentOrderResponse second = mockPaymentService.createOrder(
                                userId,
                                reservation.reservationId());

                assertEquals(first.orderId(), second.orderId());
                assertEquals("CREATED", first.status());
                assertEquals(99800L, first.amountPaise());

                Integer count = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM payment_orders
                                WHERE reservation_id = ?
                                """,
                                Integer.class,
                                reservation.reservationId());

                assertEquals(1, count);
        }

        @Test
        void failedMockPaymentCanBeRetried() {

                UUID userId = createUser(true);
                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                PaymentOrderResponse order = mockPaymentService.createOrder(
                                userId,
                                reservation.reservationId());

                PaymentOrderResponse failed = mockPaymentService.simulatePayment(
                                userId,
                                order.orderId(),
                                SimulatePaymentRequest.Outcome.FAILURE);

                // The order remains retryable.
                assertEquals("CREATED", failed.status());

                PaymentOrderResponse succeeded = mockPaymentService.simulatePayment(
                                userId,
                                order.orderId(),
                                SimulatePaymentRequest.Outcome.SUCCESS);

                assertEquals("SUCCEEDED", succeeded.status());

                String reservationStatus = jdbc.queryForObject("""
                                SELECT status
                                FROM reservations
                                WHERE id = ?
                                """,
                                String.class,
                                reservation.reservationId());

                assertEquals("CONFIRMED", reservationStatus);

                String seatStatus = jdbc.queryForObject("""
                                SELECT status
                                FROM show_seats
                                WHERE id = ?
                                """,
                                String.class,
                                show.seatIds().get(0));

                assertEquals("BOOKED", seatStatus);

                Integer attempts = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM payment_attempts
                                WHERE payment_order_id = ?
                                """,
                                Integer.class,
                                order.orderId());

                assertEquals(2, attempts);

                // Replaying success does not create another attempt.
                mockPaymentService.simulatePayment(
                                userId,
                                order.orderId(),
                                SimulatePaymentRequest.Outcome.SUCCESS);

                Integer afterReplay = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM payment_attempts
                                WHERE payment_order_id = ?
                                """,
                                Integer.class,
                                order.orderId());

                assertEquals(2, afterReplay);
        }

        @Test
        void expiredReservationCannotBePaid() {

                UUID userId = createUser(true);
                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                PaymentOrderResponse order = mockPaymentService.createOrder(
                                userId,
                                reservation.reservationId());

                jdbc.update("""
                                UPDATE reservations
                                SET expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                reservation.reservationId());

                jdbc.update("""
                                UPDATE show_seats
                                SET hold_expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE hold_token = ?
                                """,
                                reservation.reservationId());

                ResponseStatusException error = assertThrows(
                                ResponseStatusException.class,
                                () -> mockPaymentService.simulatePayment(
                                                userId,
                                                order.orderId(),
                                                SimulatePaymentRequest.Outcome.SUCCESS));

                assertEquals(
                                HttpStatus.CONFLICT,
                                error.getStatusCode());

                reservationExpiryJob.runCleanup();

                String status = jdbc.queryForObject("""
                                SELECT status
                                FROM reservations
                                WHERE id = ?
                                """,
                                String.class,
                                reservation.reservationId());

                assertEquals("EXPIRED", status);

                Integer booked = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM show_seats
                                WHERE show_id = ?
                                  AND status = 'BOOKED'
                                """,
                                Integer.class,
                                show.showId());

                assertEquals(0, booked);
        }

        @Test
        void anotherUserCannotAccessPaymentOrder() {

                UUID owner = createUser(true);
                UUID stranger = createUser(true);

                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                owner,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                PaymentOrderResponse order = mockPaymentService.createOrder(
                                owner,
                                reservation.reservationId());

                ResponseStatusException readError = assertThrows(
                                ResponseStatusException.class,
                                () -> mockPaymentService.getOrder(
                                                stranger,
                                                order.orderId()));

                assertEquals(
                                HttpStatus.NOT_FOUND,
                                readError.getStatusCode());

                ResponseStatusException simulateError = assertThrows(
                                ResponseStatusException.class,
                                () -> mockPaymentService.simulatePayment(
                                                stranger,
                                                order.orderId(),
                                                SimulatePaymentRequest.Outcome.SUCCESS));

                assertEquals(
                                HttpStatus.NOT_FOUND,
                                simulateError.getStatusCode());
        }

        @Test
        void paymentAndCancellationMustNotBothWin()
                        throws Exception {

                UUID userId = createUser(true);
                TestShow show = createShow(2);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                PaymentOrderResponse order = mockPaymentService.createOrder(
                                userId,
                                reservation.reservationId());

                ExecutorService executor = Executors.newFixedThreadPool(2);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                try {
                        Future<String> payment = executor.submit(() -> {
                                ready.countDown();
                                start.await();

                                try {
                                        mockPaymentService.simulatePayment(
                                                        userId,
                                                        order.orderId(),
                                                        SimulatePaymentRequest.Outcome.SUCCESS);
                                        return "SUCCESS";
                                } catch (ResponseStatusException ex) {
                                        assertEquals(
                                                        HttpStatus.CONFLICT,
                                                        ex.getStatusCode());
                                        return "CONFLICT";
                                }
                        });

                        Future<String> cancellation = executor.submit(() -> {
                                ready.countDown();
                                start.await();

                                try {
                                        authenticatedService.cancelReservation(
                                                        userId,
                                                        reservation.reservationId());
                                        return "SUCCESS";
                                } catch (ResponseStatusException ex) {
                                        assertEquals(
                                                        HttpStatus.CONFLICT,
                                                        ex.getStatusCode());
                                        return "CONFLICT";
                                }
                        });

                        assertTrue(ready.await(15, TimeUnit.SECONDS));
                        start.countDown();

                        String paymentResult = payment.get(45, TimeUnit.SECONDS);

                        String cancellationResult = cancellation.get(45, TimeUnit.SECONDS);

                        assertNotEquals(
                                        paymentResult,
                                        cancellationResult,
                                        "Payment and cancellation must not both succeed");

                        String reservationStatus = jdbc.queryForObject("""
                                        SELECT status
                                        FROM reservations
                                        WHERE id = ?
                                        """,
                                        String.class,
                                        reservation.reservationId());

                        String paymentStatus = jdbc.queryForObject("""
                                        SELECT status
                                        FROM payment_orders
                                        WHERE id = ?
                                        """,
                                        String.class,
                                        order.orderId());

                        if ("CONFIRMED".equals(reservationStatus)) {

                                assertEquals("SUCCEEDED", paymentStatus);

                                Integer booked = jdbc.queryForObject("""
                                                SELECT COUNT(*)
                                                FROM show_seats
                                                WHERE show_id = ?
                                                  AND status = 'BOOKED'
                                                """,
                                                Integer.class,
                                                show.showId());

                                assertEquals(2, booked);

                        } else {

                                assertEquals(
                                                "CANCELLED",
                                                reservationStatus);

                                assertEquals("CREATED", paymentStatus);

                                Integer available = jdbc.queryForObject("""
                                                SELECT COUNT(*)
                                                FROM show_seats
                                                WHERE show_id = ?
                                                  AND status = 'AVAILABLE'
                                                """,
                                                Integer.class,
                                                show.showId());

                                assertEquals(2, available);
                        }

                } finally {
                        start.countDown();
                        executor.shutdownNow();
                }
        }

        private UUID createRazorpayTestOrder(
                        UUID userId,
                        ReservationResponse reservation) {

                UUID orderId = UUID.randomUUID();

                jdbc.update("""
                                INSERT INTO payment_orders (
                                    id,
                                    reservation_id,
                                    user_id,
                                    provider,
                                    provider_order_id,
                                    amount_paise,
                                    currency,
                                    status
                                )
                                VALUES (
                                    ?, ?, ?,
                                    'RAZORPAY',
                                    ?,
                                    ?,
                                    'INR',
                                    'CREATED'
                                )
                                """,
                                orderId,
                                reservation.reservationId(),
                                userId,
                                "order_" + orderId.toString().replace("-", ""),
                                reservation.totalPrice()
                                                .movePointRight(2)
                                                .longValueExact());

                return orderId;
        }

        private PaymentFulfillmentEngine.VerifiedCapture testCapture(
                        UUID orderId,
                        long amountPaise) {

                return new PaymentFulfillmentEngine.VerifiedCapture(
                                "order_" + orderId.toString().replace("-", ""),
                                "pay_" + UUID.randomUUID().toString().replace("-", ""),
                                orderId.toString(),
                                amountPaise,
                                "INR");
        }

        @Test
        void capturedPaymentConfirmsReservationAtomically() {

                UUID userId = createUser(true);
                TestShow show = createShow(2);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                UUID orderId = createRazorpayTestOrder(
                                userId,
                                reservation);

                var evidence = testCapture(orderId, 99800L);

                var result = paymentFulfillmentEngine.applyVerifiedCapture(
                                userId,
                                orderId,
                                evidence);

                assertEquals("CONFIRMED", result.reservationStatus());
                assertEquals("SUCCEEDED", result.paymentOrderStatus());
                assertEquals("CONFIRMED", result.outcome());

                Integer bookedSeats = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM show_seats
                                WHERE show_id = ?
                                  AND status = 'BOOKED'
                                  AND hold_token IS NULL
                                  AND hold_expires_at IS NULL
                                """,
                                Integer.class,
                                show.showId());

                assertEquals(2, bookedSeats);

                Integer successfulAttempts = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM payment_attempts
                                WHERE payment_order_id = ?
                                  AND provider = 'RAZORPAY'
                                  AND status = 'SUCCEEDED'
                                """,
                                Integer.class,
                                orderId);

                assertEquals(1, successfulAttempts);
        }

        @Test
        void capturedPaymentReplayDoesNotDuplicateFulfillment() {

                UUID userId = createUser(true);
                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                UUID orderId = createRazorpayTestOrder(
                                userId,
                                reservation);

                var evidence = testCapture(orderId, 49900L);

                var first = paymentFulfillmentEngine.applyVerifiedCapture(
                                userId, orderId, evidence);

                var replay = paymentFulfillmentEngine.applyVerifiedCapture(
                                userId, orderId, evidence);

                assertFalse(first.replay());
                assertTrue(replay.replay());

                Integer attempts = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM payment_attempts
                                WHERE payment_order_id = ?
                                  AND status = 'SUCCEEDED'
                                """,
                                Integer.class,
                                orderId);

                assertEquals(1, attempts);
        }

        @Test
        void lateCapturedPaymentCreatesCompensationCase() {

                UUID userId = createUser(true);
                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                UUID orderId = createRazorpayTestOrder(
                                userId,
                                reservation);

                jdbc.update("""
                                UPDATE reservations
                                SET expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE id = ?
                                """,
                                reservation.reservationId());

                jdbc.update("""
                                UPDATE show_seats
                                SET hold_expires_at =
                                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                                WHERE hold_token = ?
                                """,
                                reservation.reservationId());

                reservationExpiryJob.runCleanup();

                var evidence = testCapture(orderId, 49900L);

                var result = paymentFulfillmentEngine.applyVerifiedCapture(
                                userId, orderId, evidence);

                assertEquals(
                                "COMPENSATION_REQUIRED",
                                result.outcome());

                Integer cases = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM payment_compensation_cases
                                WHERE payment_order_id = ?
                                  AND reason = 'LATE_CAPTURE'
                                  AND status = 'OPEN'
                                """,
                                Integer.class,
                                orderId);

                assertEquals(1, cases);

                Integer booked = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM show_seats
                                WHERE show_id = ?
                                  AND status = 'BOOKED'
                                """,
                                Integer.class,
                                show.showId());

                assertEquals(0, booked);

                // The same capture must not create a second case.
                paymentFulfillmentEngine.applyVerifiedCapture(
                                userId, orderId, evidence);

                Integer afterReplay = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM payment_compensation_cases
                                WHERE payment_order_id = ?
                                """,
                                Integer.class,
                                orderId);

                assertEquals(1, afterReplay);
        }

        @Test
        void cancelledReservationCannotBeConfirmed() {

                UUID userId = createUser(true);
                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                UUID orderId = createRazorpayTestOrder(
                                userId,
                                reservation);

                authenticatedService.cancelReservation(
                                userId,
                                reservation.reservationId());

                var evidence = testCapture(orderId, 49900L);

                var result = paymentFulfillmentEngine.applyVerifiedCapture(
                                userId, orderId, evidence);

                assertEquals(
                                "COMPENSATION_REQUIRED",
                                result.outcome());

                String reservationStatus = jdbc.queryForObject("""
                                SELECT status
                                FROM reservations
                                WHERE id = ?
                                """,
                                String.class,
                                reservation.reservationId());

                assertEquals("CANCELLED", reservationStatus);

                String reason = jdbc.queryForObject("""
                                SELECT reason
                                FROM payment_compensation_cases
                                WHERE payment_order_id = ?
                                """,
                                String.class,
                                orderId);

                assertEquals("RESERVATION_CANCELLED", reason);
        }

        @Test
        void mismatchedCapturedAmountCannotConfirmSeats() {

                UUID userId = createUser(true);
                TestShow show = createShow(1);

                ReservationResponse reservation = authenticatedService.createReservation(
                                userId,
                                request(
                                                show,
                                                UUID.randomUUID(),
                                                show.seatIds()));

                UUID orderId = createRazorpayTestOrder(
                                userId,
                                reservation);

                var wrongAmount = testCapture(orderId, 99999L);

                ResponseStatusException error = assertThrows(
                                ResponseStatusException.class,
                                () -> paymentFulfillmentEngine.applyVerifiedCapture(
                                                userId,
                                                orderId,
                                                wrongAmount));

                assertEquals(
                                HttpStatus.CONFLICT,
                                error.getStatusCode());

                String status = jdbc.queryForObject("""
                                SELECT status
                                FROM reservations
                                WHERE id = ?
                                """,
                                String.class,
                                reservation.reservationId());

                assertEquals("HELD", status);
        }

}
