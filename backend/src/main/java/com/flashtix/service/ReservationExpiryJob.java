
package com.flashtix.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ReservationExpiryJob {

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;

    public ReservationExpiryJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs periodically.
     *
     * A single transaction:
     * 1. Locks eligible reservation rows.
     * 2. Marks reservations EXPIRED.
     * 3. Releases their held seats.
     * 4. Cleans up standalone legacy holds.
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void runCleanup() {

        expireReservationBatch();
        cleanupLegacyHolds();
    }

    /**
     * FOR UPDATE SKIP LOCKED allows multiple
     * application instances to avoid processing
     * the same locked reservation simultaneously.
     *
     * EXPIRED records with lingering held seats
     * are included to repair older partial cleanup.
     */
    private void expireReservationBatch() {

        List<UUID> reservationIds = jdbc.query("""
                SELECT r.id
                FROM reservations r
                WHERE r.expires_at <= CURRENT_TIMESTAMP
                  AND (
                      r.status = 'HELD'

                      OR (
                          r.status = 'EXPIRED'
                          AND EXISTS (
                              SELECT 1
                              FROM show_seats ss
                              WHERE ss.hold_token = r.id
                                AND ss.status = 'HELD'
                          )
                      )
                  )
                ORDER BY r.expires_at, r.id
                LIMIT ?
                FOR UPDATE OF r SKIP LOCKED
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                BATCH_SIZE);

        for (UUID reservationId : reservationIds) {

            // Only HELD -> EXPIRED is permitted.
            // Previously expired records retain
            // their existing terminal status.
            jdbc.update("""
                    UPDATE reservations
                    SET status = 'EXPIRED',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                      AND status = 'HELD'
                      AND expires_at <= CURRENT_TIMESTAMP
                    """,
                    reservationId);

            // Release only inventory belonging
            // to this specific reservation.
            jdbc.update("""
                    UPDATE show_seats
                    SET status = 'AVAILABLE',
                        hold_token = NULL,
                        hold_expires_at = NULL
                    WHERE status = 'HELD'
                      AND hold_token = ?
                      AND id IN (
                          SELECT show_seat_id
                          FROM reservation_seats
                          WHERE reservation_id = ?
                      )
                    """,
                    reservationId,
                    reservationId);
        }
    }

    /**
     * Older seat-hold endpoints created hold tokens
     * without creating reservation records.
     *
     * Only release expired holds whose tokens
     * are NOT associated with a reservation.
     */
    private void cleanupLegacyHolds() {

        jdbc.update("""
                UPDATE show_seats ss
                SET status = 'AVAILABLE',
                    hold_token = NULL,
                    hold_expires_at = NULL
                WHERE ss.status = 'HELD'
                  AND ss.hold_expires_at <= CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                      SELECT 1
                      FROM reservations r
                      WHERE r.id = ss.hold_token
                  )
                """);
    }
}
