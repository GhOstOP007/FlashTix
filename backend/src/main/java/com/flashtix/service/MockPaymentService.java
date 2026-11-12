
package com.flashtix.service;

import com.flashtix.dto.PaymentOrderResponse;
import com.flashtix.dto.SimulatePaymentRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MockPaymentService {

    private static final String PROVIDER = "MOCK";

    private final JdbcTemplate jdbc;

    public MockPaymentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record ReservationRow(
            UUID id,
            UUID userId,
            String status,
            BigDecimal totalPrice,
            Instant expiresAt) {
    }

    private record OrderRow(
            UUID id,
            UUID reservationId,
            UUID userId,
            String status) {
    }

    // ==========================================
    // 1. CREATE OR RETRIEVE PAYMENT ORDER
    // ==========================================

    @Transactional
    public PaymentOrderResponse createOrder(
            UUID userId,
            UUID reservationId) {

        verifyActiveUser(userId);

        // Lock the parent reservation before doing
        // anything that may modify payment state.
        ReservationRow reservation = lockOwnedReservation(userId, reservationId);

        // Return an existing order for repeated requests.
        // This works even if the reservation was
        // subsequently confirmed or expired.
        List<UUID> existing = jdbc.query("""
                SELECT id
                FROM payment_orders
                WHERE reservation_id = ?
                  AND user_id = ?
                  AND provider = ?
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                reservationId,
                userId,
                PROVIDER);

        if (!existing.isEmpty()) {
            return getOrderInternal(existing.get(0));
        }

        // New payment orders require an active hold.
        if (!"HELD".equals(reservation.status())
                || !reservation.expiresAt()
                        .isAfter(databaseNow())) {

            throw conflict(
                    "Reservation is not available for payment");
        }

        // Verify all historical reservation seats
        // are still held by this reservation.
        verifySeatOwnership(reservationId);

        // BigDecimal -> integer paise, exactly.
        // Never use double for currency conversion.
        long amountPaise;

        try {
            amountPaise = reservation.totalPrice()
                    .movePointRight(2)
                    .longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException(
                    "Reservation price cannot be represented in paise",
                    ex);
        }

        if (amountPaise <= 0) {
            throw new IllegalStateException(
                    "Payment amount must be positive");
        }

        UUID orderId = UUID.randomUUID();

        int inserted = jdbc.update("""
                INSERT INTO payment_orders (
                    id,
                    reservation_id,
                    user_id,
                    provider,
                    amount_paise,
                    currency,
                    status
                )
                VALUES (
                    ?, ?, ?, ?, ?,
                    'INR', 'CREATED'
                )
                """,
                orderId,
                reservationId,
                userId,
                PROVIDER,
                amountPaise);

        if (inserted != 1) {
            throw new IllegalStateException(
                    "Unable to create payment order");
        }

        return getOrderInternal(orderId);
    }

    // ==========================================
    // 2. RETRIEVE CUSTOMER PAYMENT ORDER
    // ==========================================

    @Transactional(readOnly = true)
    public PaymentOrderResponse getOrder(
            UUID userId,
            UUID orderId) {

        verifyActiveUser(userId);

        List<UUID> matching = jdbc.query("""
                SELECT id
                FROM payment_orders
                WHERE id = ?
                  AND user_id = ?
                  AND provider = ?
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                orderId,
                userId,
                PROVIDER);

        if (matching.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payment order not found");
        }

        return getOrderInternal(orderId);
    }

    // ==========================================
    // 3. SIMULATE PAYMENT
    // ==========================================

    @Transactional
    public PaymentOrderResponse simulatePayment(
            UUID userId,
            UUID orderId,
            SimulatePaymentRequest.Outcome outcome) {

        if (outcome == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment outcome is required");
        }

        verifyActiveUser(userId);

        // First discover the parent reservation ID.
        // This read does not acquire an order lock.
        List<UUID> reservationIds = jdbc.query("""
                SELECT reservation_id
                FROM payment_orders
                WHERE id = ?
                  AND user_id = ?
                  AND provider = ?
                """,
                (rs, rowNum) -> rs.getObject("reservation_id", UUID.class),
                orderId,
                userId,
                PROVIDER);

        if (reservationIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payment order not found");
        }

        UUID reservationId = reservationIds.get(0);

        // Lock ordering:
        // 1. Reservation
        // 2. Payment order
        // 3. Seat inventory
        ReservationRow reservation = lockOwnedReservation(userId, reservationId);

        OrderRow order = lockOrder(userId, orderId);

        if (!order.reservationId().equals(reservationId)
                || !order.userId().equals(userId)) {

            throw new IllegalStateException(
                    "Payment order ownership mismatch");
        }

        // A repeated success is a safe replay.
        // Do not insert another successful attempt.
        if ("SUCCEEDED".equals(order.status())) {

            if (outcome == SimulatePaymentRequest.Outcome.SUCCESS) {
                return getOrderInternal(orderId);
            }

            throw conflict(
                    "A successful payment cannot be failed");
        }

        if (!"CREATED".equals(order.status())) {
            throw conflict(
                    "Payment order cannot be processed");
        }

        // Never process a new outcome after expiry.
        if (!"HELD".equals(reservation.status())
                || !reservation.expiresAt()
                        .isAfter(databaseNow())) {

            throw conflict(
                    "Reservation expired or is no longer active");
        }

        verifySeatOwnership(reservationId);

        UUID attemptId = UUID.randomUUID();

        if (outcome == SimulatePaymentRequest.Outcome.FAILURE) {

            // A failed attempt is retained for audit.
            // The logical payment order remains CREATED
            // so the customer can retry while HELD.
            jdbc.update("""
                    INSERT INTO payment_attempts (
                        id,
                        payment_order_id,
                        provider,
                        status,
                        failure_code
                    )
                    VALUES (?, ?, ?, 'FAILED', 'MOCK_FAILURE')
                    """,
                    attemptId,
                    orderId,
                    PROVIDER);

            return getOrderInternal(orderId);
        }

        // ======================================
        // SUCCESS TRANSITION
        // ======================================

        // Convert every held seat to BOOKED.
        // All changes remain uncommitted until
        // reservation and order state transitions pass.
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

        Integer expected = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM reservation_seats
                WHERE reservation_id = ?
                """,
                Integer.class,
                reservationId);

        if (expected == null
                || expected <= 0
                || booked != expected) {

            throw conflict(
                    "Reservation seats are no longer available");
        }

        // This check also prevents confirmation
        // if the deadline passes during processing.
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
                    "Reservation expired during payment");
        }

        int updatedOrder = jdbc.update("""
                UPDATE payment_orders
                SET status = 'SUCCEEDED',
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'CREATED'
                """,
                orderId);

        if (updatedOrder != 1) {
            throw new IllegalStateException(
                    "Payment order transition failed");
        }

        jdbc.update("""
                INSERT INTO payment_attempts (
                    id,
                    payment_order_id,
                    provider,
                    status
                )
                VALUES (?, ?, ?, 'SUCCEEDED')
                """,
                attemptId,
                orderId,
                PROVIDER);

        return getOrderInternal(orderId);
    }

    // ==========================================
    // PRIVATE HELPERS
    // ==========================================

    private ReservationRow lockOwnedReservation(
            UUID userId,
            UUID reservationId) {

        List<ReservationRow> rows = jdbc.query("""
                SELECT id, user_id, status,
                       total_price, expires_at
                FROM reservations
                WHERE id = ?
                  AND user_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new ReservationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("total_price"),
                        rs.getTimestamp("expires_at").toInstant()),
                reservationId,
                userId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Reservation not found");
        }

        return rows.get(0);
    }

    private OrderRow lockOrder(
            UUID userId,
            UUID orderId) {

        List<OrderRow> rows = jdbc.query("""
                SELECT id, reservation_id, user_id, status
                FROM payment_orders
                WHERE id = ?
                  AND user_id = ?
                  AND provider = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new OrderRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("status")),
                orderId,
                userId,
                PROVIDER);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payment order not found");
        }

        return rows.get(0);
    }

    private void verifySeatOwnership(
            UUID reservationId) {

        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM reservation_seats
                WHERE reservation_id = ?
                """,
                Integer.class,
                reservationId);

        Integer valid = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM reservation_seats rs
                JOIN reservations r
                  ON r.id = rs.reservation_id
                JOIN show_seats ss
                  ON ss.id = rs.show_seat_id
                 AND ss.show_id = r.show_id
                WHERE rs.reservation_id = ?
                  AND ss.status = 'HELD'
                  AND ss.hold_token = r.id
                  AND ss.hold_expires_at > clock_timestamp()
                """,
                Integer.class,
                reservationId);

        if (total == null || total == 0
                || !total.equals(valid)) {

            throw conflict(
                    "One or more reservation seats are unavailable");
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
                userId);

        if (count == null || count != 1) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "User account is disabled or unavailable");
        }
    }

    private Instant databaseNow() {

        java.sql.Timestamp timestamp = jdbc.queryForObject(
                "SELECT clock_timestamp()",
                java.sql.Timestamp.class);

        if (timestamp == null) {
            throw new IllegalStateException(
                    "Database clock is unavailable");
        }

        return timestamp.toInstant();
    }

    // private Instant databaseNow() {
    // return jdbc.queryForObject(
    // "SELECT clock_timestamp()",
    // Instant.class);
    // }

    private PaymentOrderResponse getOrderInternal(
            UUID orderId) {

        List<PaymentOrderResponse> rows = jdbc.query("""
                SELECT id, reservation_id,
                       amount_paise, currency,
                       provider, status, created_at
                FROM payment_orders
                WHERE id = ?
                """,
                (rs, rowNum) -> new PaymentOrderResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("currency"),
                        rs.getString("provider"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()),
                orderId);

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Payment order not found internally");
        }

        return rows.get(0);
    }

    private ResponseStatusException conflict(
            String message) {

        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message);
    }
}
