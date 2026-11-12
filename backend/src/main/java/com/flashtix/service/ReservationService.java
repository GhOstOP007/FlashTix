
package com.flashtix.service;

import com.flashtix.dto.CancelReservationRequest;
import com.flashtix.dto.CreateReservationRequest;
import com.flashtix.dto.ReservationResponse;

import com.flashtix.entity.EventShow;
import com.flashtix.entity.Reservation;
import com.flashtix.entity.ReservationSeat;

import com.flashtix.repository.EventShowRepository;
import com.flashtix.repository.ReservationRepository;
import com.flashtix.repository.ReservationSeatRepository;
import com.flashtix.repository.ShowSeatRepository;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

        private final ReservationRepository reservationRepository;
        private final ReservationSeatRepository reservationSeatRepository;
        private final EventShowRepository showRepository;
        private final ShowSeatRepository showSeatRepository;
        private final JdbcTemplate jdbc;

        public ReservationService(
                        ReservationRepository reservationRepository,
                        ReservationSeatRepository reservationSeatRepository,
                        EventShowRepository showRepository,
                        ShowSeatRepository showSeatRepository,
                        JdbcTemplate jdbc) {

                this.reservationRepository = reservationRepository;
                this.reservationSeatRepository = reservationSeatRepository;
                this.showRepository = showRepository;
                this.showSeatRepository = showSeatRepository;
                this.jdbc = jdbc;
        }

        // ==================================================
        // 1. CREATE RESERVATION
        // ==================================================

        @Transactional
        public ReservationResponse createReservation(
                        CreateReservationRequest request) {

                if (request.clientSessionId() == null
                                || request.idempotencyKey() == null
                                || request.showId() == null
                                || request.showId() <= 0) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Invalid reservation request");
                }

                List<Long> seatIds = request.showSeatIds();

                if (seatIds == null
                                || seatIds.isEmpty()
                                || seatIds.size() > 6
                                || seatIds.stream().anyMatch(
                                                id -> id == null || id <= 0)) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Select between 1 and 6 valid seats");
                }

                if (new HashSet<>(seatIds).size() != seatIds.size()) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Duplicate seats are not allowed");
                }

                // Consistent ordering prevents reversed lock order.
                List<Long> sortedIds = new ArrayList<>(seatIds);
                sortedIds.sort(Comparator.naturalOrder());

                String hash = calculateRequestHash(
                                request.showId(),
                                sortedIds);

                // Return an earlier reservation for an exact retry.
                var existing = reservationRepository
                                .findByClientSessionIdAndIdempotencyKey(
                                                request.clientSessionId(),
                                                request.idempotencyKey());

                if (existing.isPresent()) {
                        return handleExisting(existing.get(), hash);
                }

                EventShow show = showRepository
                                .findById(request.showId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Show not found"));

                if (!"SCHEDULED".equals(show.getStatus())
                                || !show.getStartTime()
                                                .isAfter(Instant.now())) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Show is not available for booking");
                }

                UUID reservationId = UUID.randomUUID();

                // Claim the idempotency key atomically.
                // A concurrent retry waits for this transaction.
                List<UUID> inserted = jdbc.query("""
                                INSERT INTO reservations (
                                    id,
                                    show_id,
                                    client_session_id,
                                    idempotency_key,
                                    request_hash,
                                    status,
                                    total_price,
                                    expires_at
                                )
                                VALUES (
                                    ?, ?, ?, ?, ?,
                                    'HELD',
                                    0,
                                    CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                                )
                                ON CONFLICT (
                                    client_session_id,
                                    idempotency_key
                                )
                                DO NOTHING
                                RETURNING id
                                """,
                                (rs, rowNum) -> rs.getObject("id", UUID.class),
                                reservationId,
                                request.showId(),
                                request.clientSessionId(),
                                request.idempotencyKey(),
                                hash);

                if (inserted.isEmpty()) {

                        Reservation original = reservationRepository
                                        .findByClientSessionIdAndIdempotencyKey(
                                                        request.clientSessionId(),
                                                        request.idempotencyKey())
                                        .orElseThrow(() -> new IllegalStateException(
                                                        "Idempotency record unavailable"));

                        return handleExisting(original, hash);
                }

                // Acquire every seat inside the same transaction.
                // Use the reservation ID as the hold token.
                for (Long seatId : sortedIds) {

                        int updated = showSeatRepository.acquireSeatHold(
                                        request.showId(),
                                        seatId,
                                        reservationId);

                        if (updated != 1) {
                                throw new ResponseStatusException(
                                                HttpStatus.CONFLICT,
                                                "One or more seats are unavailable");
                        }

                        // Capture the price at reservation time.
                        int linked = jdbc.update("""
                                        INSERT INTO reservation_seats (
                                            reservation_id,
                                            show_id,
                                            show_seat_id,
                                            price_at_reservation
                                        )
                                        SELECT
                                            ?,
                                            show_id,
                                            id,
                                            price
                                        FROM show_seats
                                        WHERE id = ?
                                          AND show_id = ?
                                          AND status = 'HELD'
                                          AND hold_token = ?
                                        """,
                                        reservationId,
                                        seatId,
                                        request.showId(),
                                        reservationId);

                        if (linked != 1) {
                                throw new IllegalStateException(
                                                "Failed to associate reserved seat");
                        }

                        int synchronizedExpiry = jdbc.update("""
                                        UPDATE show_seats
                                        SET hold_expires_at = (
                                            SELECT expires_at
                                            FROM reservations
                                            WHERE id = ?
                                        )
                                        WHERE id = ?
                                          AND show_id = ?
                                          AND status = 'HELD'
                                          AND hold_token = ?
                                        """,
                                        reservationId,
                                        seatId,
                                        request.showId(),
                                        reservationId);

                        if (synchronizedExpiry != 1) {
                                throw new IllegalStateException(
                                                "Failed to synchronize seat hold expiry");
                        }

                }

                // Calculate the final price using stored snapshots.
                int priced = jdbc.update("""
                                UPDATE reservations
                                SET total_price = (
                                    SELECT COALESCE(
                                        SUM(price_at_reservation), 0
                                    )
                                    FROM reservation_seats
                                    WHERE reservation_id = ?
                                ),
                                updated_at = CURRENT_TIMESTAMP
                                WHERE id = ?
                                """,
                                reservationId,
                                reservationId);

                if (priced != 1) {
                        throw new IllegalStateException(
                                        "Failed to calculate reservation price");
                }

                return getReservationInternal(reservationId);
        }

        // ==================================================
        // 2. GET ONE RESERVATION
        // ==================================================

        @Transactional(readOnly = true)
        public ReservationResponse getReservation(
                        UUID reservationId,
                        UUID clientSessionId) {

                Reservation reservation = reservationRepository
                                .findById(reservationId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Reservation not found"));

                verifyOwner(reservation, clientSessionId);

                return toResponse(reservation);
        }

        // ==================================================
        // 3. GET RESERVATION HISTORY
        // ==================================================

        @Transactional(readOnly = true)
        public List<ReservationResponse> getHistory(
                        UUID clientSessionId) {

                return reservationRepository
                                .findByClientSessionIdOrderByCreatedAtDesc(
                                                clientSessionId)
                                .stream()
                                .map(this::toResponse)
                                .toList();
        }

        // ==================================================
        // 4. CANCEL ENTIRE RESERVATION
        // ==================================================

        // @Transactional
        // public ReservationResponse cancelReservation(
        // UUID reservationId,
        // CancelReservationRequest request) {

        // UUID clientSessionId = request.clientSessionId();

        // if (clientSessionId == null) {
        // throw new ResponseStatusException(
        // HttpStatus.BAD_REQUEST,
        // "Client session is required");
        // }

        // // Lock the reservation row so two cancellation
        // // operations cannot proceed independently.
        // List<UUID> locked = jdbc.query("""
        // SELECT id
        // FROM reservations
        // WHERE id = ?
        // AND client_session_id = ?
        // FOR UPDATE
        // """,
        // (rs, rowNum) -> rs.getObject("id", UUID.class),
        // reservationId,
        // clientSessionId);

        // if (locked.isEmpty()) {
        // throw new ResponseStatusException(
        // HttpStatus.NOT_FOUND,
        // "Reservation not found");
        // }

        // // Cancellation is allowed only during a live hold.
        // int changed = jdbc.update("""
        // UPDATE reservations
        // SET status = 'CANCELLED',
        // updated_at = CURRENT_TIMESTAMP
        // WHERE id = ?
        // AND status = 'HELD'
        // AND expires_at > CURRENT_TIMESTAMP
        // """,
        // reservationId);

        // if (changed != 1) {
        // throw new ResponseStatusException(
        // HttpStatus.CONFLICT,
        // "Reservation is no longer cancellable");
        // }

        // Integer expectedSeats = jdbc.queryForObject("""
        // SELECT COUNT(*)
        // FROM reservation_seats
        // WHERE reservation_id = ?
        // """,
        // Integer.class,
        // reservationId);

        // // Release only seats still owned by THIS reservation.
        // int released = jdbc.update("""
        // UPDATE show_seats
        // SET status = 'AVAILABLE',
        // hold_token = NULL,
        // hold_expires_at = NULL
        // WHERE show_id = (
        // SELECT show_id
        // FROM reservations
        // WHERE id = ?
        // )
        // AND status = 'HELD'
        // AND hold_token = ?
        // AND id IN (
        // SELECT show_seat_id
        // FROM reservation_seats
        // WHERE reservation_id = ?
        // )
        // """,
        // reservationId,
        // reservationId,
        // reservationId);

        // if (expectedSeats == null
        // || released != expectedSeats) {

        // // Rolling back also restores the reservation status.
        // throw new IllegalStateException(
        // "Reservation cancellation was inconsistent");
        // }

        // return getReservationInternal(reservationId);
        // }

        @Transactional
        public ReservationResponse cancelReservation(
                        UUID reservationId,
                        CancelReservationRequest request) {

                if (request == null ||
                                request.clientSessionId() == null) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Client session is required");
                }

                UUID clientSessionId = request.clientSessionId();

                // 1. Lock parent reservation first.
                // Cancellation and expiration use the same lock order.
                List<UUID> locked = jdbc.query("""
                                SELECT id
                                FROM reservations
                                WHERE id = ?
                                  AND client_session_id = ?
                                FOR UPDATE
                                """,
                                (rs, rowNum) -> rs.getObject("id", UUID.class),
                                reservationId,
                                clientSessionId);

                if (locked.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Reservation not found");
                }

                // 2. Transition only a live HELD reservation.
                // This is checked using PostgreSQL's clock.
                int transitioned = jdbc.update("""
                                UPDATE reservations
                                SET status = 'CANCELLED',
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE id = ?
                                  AND status = 'HELD'
                                  AND expires_at > CURRENT_TIMESTAMP
                                """,
                                reservationId);

                if (transitioned != 1) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Reservation has expired or is not cancellable");
                }

                // 3. Get the exact set of inventory rows
                // associated with this reservation.
                List<Long> seatIds = jdbc.query("""
                                SELECT show_seat_id
                                FROM reservation_seats
                                WHERE reservation_id = ?
                                ORDER BY show_seat_id
                                """,
                                (rs, rowNum) -> rs.getLong("show_seat_id"),
                                reservationId);

                if (seatIds.isEmpty()) {
                        throw new IllegalStateException(
                                        "Reservation has no associated seats");
                }

                // 4. Release only seats held by this reservation.
                // The token check prevents releasing another
                // customer's subsequent reservation.
                int released = jdbc.update("""
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

                if (released != seatIds.size()) {
                        // Roll back the status transition and all seat updates.
                        throw new IllegalStateException(
                                        "Seat release mismatch: expected "
                                                        + seatIds.size()
                                                        + ", released " + released);
                }

                // 5. Read the final state within this transaction.
                return getReservationInternal(reservationId);
        }

        // ==================================================
        // 5. EXPIRE OLD RESERVATIONS
        // ==================================================
        @Transactional
        public void expireReservations() {

                jdbc.update("""
                                UPDATE reservations
                                SET status = 'EXPIRED',
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE status = 'HELD'
                                  AND expires_at <= CURRENT_TIMESTAMP
                                """);
        }

        // ==================================================
        // PRIVATE HELPERS
        // ==================================================

        private ReservationResponse handleExisting(
                        Reservation reservation,
                        String requestHash) {

                if (!reservation.getRequestHash()
                                .equals(requestHash)) {

                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Idempotency key reused with different seats");
                }

                return toResponse(reservation);
        }

        private ReservationResponse getReservationInternal(
                        UUID reservationId) {

                Reservation reservation = reservationRepository
                                .findById(reservationId)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Reservation was not persisted"));

                return toResponse(reservation);
        }

        private ReservationResponse toResponse(
                        Reservation reservation) {

                List<Long> seatIds = reservationSeatRepository
                                .findByReservationId(reservation.getId())
                                .stream()
                                .map(ReservationSeat::getShowSeatId)
                                .sorted()
                                .toList();

                String effectiveStatus = reservation.getStatus();

                if ("HELD".equals(effectiveStatus)
                                && !reservation.getExpiresAt()
                                                .isAfter(Instant.now())) {

                        effectiveStatus = "EXPIRED";
                }

                return new ReservationResponse(
                                reservation.getId(),
                                reservation.getShowId(),
                                seatIds,
                                effectiveStatus,
                                reservation.getTotalPrice(),
                                reservation.getExpiresAt(),
                                reservation.getCreatedAt());
        }

        private void verifyOwner(
                        Reservation reservation,
                        UUID clientSessionId) {

                if (clientSessionId == null
                                || !reservation.getClientSessionId()
                                                .equals(clientSessionId)) {

                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Reservation not found");
                }
        }

        private String calculateRequestHash(
                        Long showId,
                        List<Long> sortedSeatIds) {

                String normalized = showId + ":"
                                + sortedSeatIds.stream()
                                                .map(String::valueOf)
                                                .reduce(
                                                                (a, b) -> a + "," + b)
                                                .orElse("");

                try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");

                        byte[] bytes = digest.digest(
                                        normalized.getBytes(StandardCharsets.UTF_8));

                        return HexFormat.of().formatHex(bytes);

                } catch (NoSuchAlgorithmException ex) {
                        throw new IllegalStateException(
                                        "SHA-256 is unavailable",
                                        ex);
                }
        }
}
