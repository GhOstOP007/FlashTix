
package com.flashtix;

import com.flashtix.dto.MultiSeatHoldRequest;
import com.flashtix.service.SeatHoldService;

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
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=16"
})
@Testcontainers
class SeatHoldConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private JdbcTemplate jdbc;

    // Creates a real show with physical seats and inventory.
    private TestShow createTestShow(int seatCount) {

        Long eventId = jdbc.queryForObject("""
                INSERT INTO events (
                    title, category, start_time,
                    venue_name, city
                )
                VALUES (
                    'Concurrency Test', 'CONCERT',
                    '2028-12-15T18:30:00Z',
                    'Test Arena', 'New Delhi'
                )
                RETURNING id
                """, Long.class);

        Long venueId = jdbc.queryForObject("""
                INSERT INTO venues (name, city)
                VALUES ('Test Arena', 'New Delhi')
                RETURNING id
                """, Long.class);

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
                java.sql.Timestamp.from(
                        Instant.parse("2028-12-15T18:30:00Z")));

        List<Long> inventoryIds = new ArrayList<>();

        for (int number = 1; number <= seatCount; number++) {

            Long physicalSeatId = jdbc.queryForObject("""
                    INSERT INTO seats (
                        venue_id, row_label,
                        seat_number, seat_type
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

            inventoryIds.add(inventoryId);
        }

        return new TestShow(showId, inventoryIds);
    }

    private record TestShow(
            Long showId,
            List<Long> seatIds) {
    }

    @Test
    void onlyOneUserCanHoldSameSeat() throws Exception {

        // Arrange: one available seat and 32 contenders.
        TestShow show = createTestShow(1);

        Long showId = show.showId();
        Long seatId = show.seatIds().get(0);

        int userCount = 32;

        ExecutorService executor = Executors.newFixedThreadPool(userCount);

        CountDownLatch ready = new CountDownLatch(userCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < userCount; i++) {

                futures.add(executor.submit(() -> {

                    // Every task signals that it is ready.
                    ready.countDown();

                    // All requests start together.
                    start.await();

                    try {
                        seatHoldService.holdMultipleSeats(
                                showId,
                                new MultiSeatHoldRequest(
                                        List.of(seatId)));

                        return 201;

                    } catch (ResponseStatusException ex) {

                        if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                            return 409;
                        }

                        throw ex;
                    }
                }));
            }

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "Not all workers became ready");

            long startedAt = System.nanoTime();

            // Release all workers simultaneously.
            start.countDown();

            int successful = 0;
            int conflicts = 0;

            for (Future<Integer> future : futures) {
                int result = future.get(
                        60, TimeUnit.SECONDS);

                if (result == 201)
                    successful++;
                if (result == 409)
                    conflicts++;
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);

            System.out.println(
                    "Concurrent requests: " + userCount);
            System.out.println(
                    "Successful holds: " + successful);
            System.out.println(
                    "Conflicts: " + conflicts);
            System.out.println(
                    "Elapsed: " + elapsedMs + " ms");

            // Core correctness assertions.
            assertEquals(1, successful);
            assertEquals(userCount - 1, conflicts);

            Integer heldRows = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM show_seats
                    WHERE id = ?
                      AND status = 'HELD'
                      AND hold_expires_at > CURRENT_TIMESTAMP
                    """,
                    Integer.class,
                    seatId);

            assertEquals(1, heldRows);

            UUID token = jdbc.queryForObject("""
                    SELECT hold_token
                    FROM show_seats
                    WHERE id = ?
                    """,
                    UUID.class,
                    seatId);

            assertNotNull(token);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void overlappingReservationsMustRollback() {

        // Three available seats: A1, A2, A3
        TestShow show = createTestShow(3);

        Long showId = show.showId();
        Long firstSeat = show.seatIds().get(0);
        Long secondSeat = show.seatIds().get(1);
        Long thirdSeat = show.seatIds().get(2);

        // User A successfully holds A1 and A2.
        var firstHold = seatHoldService.holdMultipleSeats(
                showId,
                new MultiSeatHoldRequest(
                        List.of(firstSeat, secondSeat)));

        assertEquals(2, firstHold.showSeatIds().size());

        // User B attempts A2 and A3.
        // A2 is already held, so the whole request must fail.
        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> seatHoldService.holdMultipleSeats(
                        showId,
                        new MultiSeatHoldRequest(
                                List.of(secondSeat, thirdSeat))));

        assertEquals(
                HttpStatus.CONFLICT,
                conflict.getStatusCode());

        // A1 and A2 remain held by User A.
        Integer held = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM show_seats
                WHERE id IN (?, ?)
                  AND status = 'HELD'
                """,
                Integer.class,
                firstSeat,
                secondSeat);

        assertEquals(2, held);

        // A3 must remain available.
        String thirdSeatStatus = jdbc.queryForObject("""
                SELECT status
                FROM show_seats
                WHERE id = ?
                """,
                String.class,
                thirdSeat);

        assertEquals("AVAILABLE", thirdSeatStatus);
    }

}
