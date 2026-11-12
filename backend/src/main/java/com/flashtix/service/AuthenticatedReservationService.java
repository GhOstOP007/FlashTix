
package com.flashtix.service;

import com.flashtix.dto.AuthenticatedReservationRequest;
import com.flashtix.dto.CancelReservationRequest;
import com.flashtix.dto.CreateReservationRequest;
import com.flashtix.dto.ReservationResponse;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class AuthenticatedReservationService {

    private final ReservationService reservationService;
    private final JdbcTemplate jdbc;

    public AuthenticatedReservationService(
            ReservationService reservationService,
            JdbcTemplate jdbc) {

        this.reservationService = reservationService;
        this.jdbc = jdbc;
    }

    // -------------------------------------------
    // CREATE RESERVATION
    // -------------------------------------------

    @Transactional
    public ReservationResponse createReservation(
            UUID userId,
            AuthenticatedReservationRequest request) {

        verifyActiveUser(userId);

        // Check for an old development reservation that
        // has no authenticated user ownership.
        Integer legacyCount = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM reservations
            WHERE client_session_id = ?
              AND idempotency_key = ?
              AND user_id IS NULL
            """,
            Integer.class,
            userId,
            request.idempotencyKey()
        );

        if (legacyCount != null && legacyCount > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Idempotency key belongs to a legacy reservation"
            );
        }

        // The user UUID comes from the verified JWT.
        // It is used as the internal session identifier
        // only to reuse the existing reservation engine.
        CreateReservationRequest internalRequest =
            new CreateReservationRequest(
                userId,
                request.idempotencyKey(),
                request.showId(),
                request.showSeatIds()
            );

        ReservationResponse response =
            reservationService.createReservation(
                internalRequest
            );

        // Attach authenticated ownership within the
        // SAME transaction as reservation creation.
        jdbc.update("""
            UPDATE reservations
            SET user_id = ?
            WHERE id = ?
              AND user_id IS NULL
            """,
            userId,
            response.reservationId()
        );

        // Verify the reservation is owned by this user.
        Integer ownershipCount = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM reservations
            WHERE id = ?
              AND user_id = ?
            """,
            Integer.class,
            response.reservationId(),
            userId
        );

        if (ownershipCount == null
                || ownershipCount != 1) {

            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Reservation ownership verification failed"
            );
        }

        return response;
    }

    // -------------------------------------------
    // GET ONE RESERVATION
    // -------------------------------------------

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(
            UUID userId,
            UUID reservationId) {

        verifyActiveUser(userId);

        verifyOwnership(userId, reservationId);

        return reservationService.getReservation(
            reservationId,
            userId
        );
    }

    // -------------------------------------------
    // GET RESERVATION HISTORY
    // -------------------------------------------

    @Transactional(readOnly = true)
    public List<ReservationResponse> getHistory(
            UUID userId) {

        verifyActiveUser(userId);

        List<UUID> reservationIds = jdbc.query("""
            SELECT id
            FROM reservations
            WHERE user_id = ?
            ORDER BY created_at DESC
            """,
            (rs, rowNum) ->
                rs.getObject("id", UUID.class),
            userId
        );

        return reservationIds.stream()
            .map(id ->
                reservationService.getReservation(id, userId)
            )
            .toList();
    }

    // -------------------------------------------
    // CANCEL RESERVATION
    // -------------------------------------------

    @Transactional
    public ReservationResponse cancelReservation(
            UUID userId,
            UUID reservationId) {

        verifyActiveUser(userId);

        verifyOwnership(userId, reservationId);

        return reservationService.cancelReservation(
            reservationId,
            new CancelReservationRequest(userId)
        );
    }

    // -------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------

    private void verifyOwnership(
            UUID userId,
            UUID reservationId) {

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM reservations
            WHERE id = ?
              AND user_id = ?
            """,
            Integer.class,
            reservationId,
            userId
        );

        if (count == null || count != 1) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Reservation not found"
            );
        }
    }

    private void verifyActiveUser(UUID userId) {

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM users
            WHERE id = ?
              AND enabled = TRUE
            """,
            Integer.class,
            userId
        );

        if (count == null || count != 1) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "User account is disabled or unavailable"
            );
        }
    }
}
