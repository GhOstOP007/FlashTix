
package com.flashtix.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentFulfillmentEngine {

    private final JdbcTemplate jdbc;

    public PaymentFulfillmentEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record VerifiedCapture(
            String providerOrderId,
            String paymentId,
            String receipt,
            long amountPaise,
            String currency) {
    }

    public record FulfillmentResult(
            UUID paymentOrderId,
            UUID reservationId,
            String reservationStatus,
            String paymentOrderStatus,
            String outcome,
            boolean replay) {
    }

    private record OrderRow(
            UUID id,
            UUID reservationId,
            String providerOrderId,
            long amountPaise,
            String currency,
            String status) {
    }

    /**
     * Internal trusted-service operation.
     *
     * Never expose VerifiedCapture as a client-supplied
     * request body. It must come from server-side
     * Razorpay verification.
     */
    @Transactional
    public FulfillmentResult applyVerifiedCapture(
            UUID userId,
            UUID orderId,
            VerifiedCapture evidence) {

        if (evidence == null
                || evidence.paymentId() == null
                || evidence.paymentId().isBlank()) {
            throw conflict("Missing verified payment evidence");
        }

        // Discover the parent ID without acquiring an
        // order lock. Then acquire locks consistently.
        List<UUID> reservationIds = jdbc.query("""
                SELECT p.reservation_id
                FROM payment_orders p
                JOIN users u ON u.id = p.user_id
                WHERE p.id = ?
                  AND p.user_id = ?
                  AND p.provider = 'RAZORPAY'
                  AND u.enabled = TRUE
                """,
                (rs, n) -> rs.getObject("reservation_id", UUID.class),
                orderId,
                userId);

        if (reservationIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payment order not found");
        }

        UUID reservationId = reservationIds.get(0);

        // Lock order:
        // 1. Reservation
        // 2. Payment order
        // 3. Seat inventory
        List<String> reservationStatuses = jdbc.query("""
                SELECT status
                FROM reservations
                WHERE id = ?
                  AND user_id = ?
                FOR UPDATE
                """,
                (rs, n) -> rs.getString("status"),
                reservationId,
                userId);

        if (reservationStatuses.isEmpty()) {
            throw conflict("Reservation ownership changed");
        }

        String reservationStatus = reservationStatuses.get(0);

        List<OrderRow> orders = jdbc.query("""
                SELECT id, reservation_id, provider_order_id,
                       amount_paise, currency, status
                FROM payment_orders
                WHERE id = ?
                  AND user_id = ?
                  AND provider = 'RAZORPAY'
                FOR UPDATE
                """,
                (rs, n) -> new OrderRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getString("provider_order_id"),
                        rs.getLong("amount_paise"),
                        rs.getString("currency"),
                        rs.getString("status")),
                orderId,
                userId);

        if (orders.isEmpty()) {
            throw conflict("Payment order is unavailable");
        }

        OrderRow order = orders.get(0);

        if (!order.reservationId().equals(reservationId)
                || !evidence.providerOrderId().equals(
                        order.providerOrderId())
                || !order.id().toString().equals(
                        evidence.receipt())
                || evidence.amountPaise() != order.amountPaise()
                || !order.currency().equals(
                        evidence.currency())) {

            throw conflict("Verified payment evidence mismatch");
        }

        // Safe replay of a previous successful fulfilment.
        if ("SUCCEEDED".equals(order.status())) {

            if (!"CONFIRMED".equals(reservationStatus)) {
                throw conflict(
                        "Inconsistent confirmed payment state");
            }

            Integer matchingCapturedAttempts = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM payment_attempts
                    WHERE payment_order_id = ?
                      AND provider = 'RAZORPAY'
                      AND provider_payment_id = ?
                      AND status = 'SUCCEEDED'
                    """,
                    Integer.class,
                    orderId,
                    evidence.paymentId());

            if (matchingCapturedAttempts == null
                    || matchingCapturedAttempts != 1) {
                throw conflict(
                        "Successful payment replay has a different payment ID");
            }

            Integer booked = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM reservation_seats rs
                    JOIN show_seats ss
                      ON ss.id = rs.show_seat_id
                     AND ss.show_id = rs.show_id
                    WHERE rs.reservation_id = ?
                      AND ss.status = 'BOOKED'
                    """,
                    Integer.class,
                    reservationId);

            Integer expected = seatCount(reservationId);

            if (expected == null || expected == 0
                    || !expected.equals(booked)) {
                throw conflict(
                        "Confirmed reservation inventory mismatch");
            }

            return new FulfillmentResult(
                    orderId, reservationId,
                    "CONFIRMED", "SUCCEEDED",
                    "ALREADY_CONFIRMED", true);
        }

        if (!List.of("CREATED", "PENDING")
                .contains(order.status())) {
            throw conflict("Payment order is not fulfillable");
        }

        // Read the authoritative deadline while the
        // reservation is locked. Use the DB clock.
        Boolean active = jdbc.queryForObject("""
                SELECT (
                    status = 'HELD'
                    AND expires_at > clock_timestamp()
                )
                FROM reservations
                WHERE id = ?
                """,
                Boolean.class,
                reservationId);

        if (!Boolean.TRUE.equals(active)) {

            String reason = "CANCELLED".equals(reservationStatus)
                    ? "RESERVATION_CANCELLED"
                    : "LATE_CAPTURE";

            recordCapturedAttempt(
                    orderId,
                    evidence.paymentId());

            // Do not pretend that a late payment
            // successfully fulfilled the reservation.
            jdbc.update("""
                    UPDATE payment_orders
                    SET status = 'PENDING',
                        updated_at = clock_timestamp()
                    WHERE id = ?
                    """,
                    orderId);

            jdbc.update("""
                    INSERT INTO payment_compensation_cases (
                        id, payment_order_id, provider,
                        provider_payment_id, reason, status
                    )
                    VALUES (
                        ?, ?, 'RAZORPAY', ?, ?, 'OPEN'
                    )
                    ON CONFLICT DO NOTHING
                    """,
                    UUID.randomUUID(),
                    orderId,
                    evidence.paymentId(),
                    reason);

            // Detect any conflicting historical case.
            List<String> casePaymentIds = jdbc.query("""
                    SELECT provider_payment_id
                    FROM payment_compensation_cases
                    WHERE payment_order_id = ?
                    """,
                    (rs, n) -> rs.getString("provider_payment_id"),
                    orderId);

            if (casePaymentIds.size() != 1
                    || !casePaymentIds.get(0).equals(
                            evidence.paymentId())) {
                throw conflict(
                        "Conflicting compensation case");
            }

            return new FulfillmentResult(
                    orderId, reservationId,
                    reservationStatus, "PENDING",
                    "COMPENSATION_REQUIRED", false);
        }

        Integer expected = seatCount(reservationId);

        if (expected == null || expected <= 0) {
            throw conflict("Reservation has no seats");
        }

        // Claim only the inventory still held by
        // this reservation. Never touch another hold.
        int booked = jdbc.update("""
                UPDATE show_seats
                SET status = 'BOOKED',
                    hold_token = NULL,
                    hold_expires_at = NULL
                WHERE status = 'HELD'
                  AND hold_token = ?
                  AND hold_expires_at > clock_timestamp()
                  AND id IN (
                      SELECT show_seat_id
                      FROM reservation_seats
                      WHERE reservation_id = ?
                  )
                """,
                reservationId,
                reservationId);

        if (booked != expected) {
            // Roll back all seat updates.
            throw conflict(
                    "Seats are no longer available");
        }

        // Recheck the deadline after seat updates.
        int confirmed = jdbc.update("""
                UPDATE reservations
                SET status = 'CONFIRMED',
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND user_id = ?
                  AND status = 'HELD'
                  AND expires_at > clock_timestamp()
                """,
                reservationId,
                userId);

        if (confirmed != 1) {
            throw conflict(
                    "Reservation expired during fulfilment");
        }

        recordCapturedAttempt(
                orderId,
                evidence.paymentId());

        int updated = jdbc.update("""
                UPDATE payment_orders
                SET status = 'SUCCEEDED',
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status IN ('CREATED', 'PENDING')
                """,
                orderId);

        if (updated != 1) {
            throw conflict(
                    "Payment order transition failed");
        }

        return new FulfillmentResult(
                orderId, reservationId,
                "CONFIRMED", "SUCCEEDED",
                "CONFIRMED", false);
    }

    private Integer seatCount(UUID reservationId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM reservation_seats
                WHERE reservation_id = ?
                """,
                Integer.class,
                reservationId);
    }

    private void recordCapturedAttempt(
            UUID orderId,
            String providerPaymentId) {

        jdbc.update("""
                INSERT INTO payment_attempts (
                    id, payment_order_id, provider,
                    provider_payment_id, status
                )
                VALUES (
                    ?, ?, 'RAZORPAY', ?, 'SUCCEEDED'
                )
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                orderId,
                providerPaymentId);

        List<UUID> matching = jdbc.query("""
                SELECT payment_order_id
                FROM payment_attempts
                WHERE provider = 'RAZORPAY'
                  AND provider_payment_id = ?
                  AND status = 'SUCCEEDED'
                """,
                (rs, n) -> rs.getObject("payment_order_id", UUID.class),
                providerPaymentId);

        if (matching.size() != 1
                || !matching.get(0).equals(orderId)) {
            throw conflict(
                    "Captured payment belongs to another order");
        }
    }

    private ResponseStatusException conflict(
            String message) {

        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message);
    }
}
