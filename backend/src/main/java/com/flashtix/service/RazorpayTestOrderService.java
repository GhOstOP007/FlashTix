
package com.flashtix.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
// import java.sql.Timestamp;
// import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
public class RazorpayTestOrderService {

        private static final String PROVIDER = "RAZORPAY";

        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;
        private final RazorpayOrderClient razorpayClient;

        private final String publicKeyId;

        public record OrderResponse(
                        UUID orderId,
                        UUID reservationId,
                        String providerOrderId,
                        long amountPaise,
                        String currency,
                        String status,
                        String razorpayKeyId,
                        boolean checkoutEligible) {
        }

        private record LocalOrder(
                        UUID id,
                        UUID reservationId,
                        long amountPaise,
                        String status,
                        String providerOrderId,
                        String currency) {
        }

        private record Preparation(
                        LocalOrder order,
                        boolean newlyCreated) {
        }

        public RazorpayTestOrderService(
                        JdbcTemplate jdbc,
                        PlatformTransactionManager manager,
                        RazorpayOrderClient razorpayClient,
                        @Value("${flashtix.razorpay.key-id}") String publicKeyId) {

                this.jdbc = jdbc;
                this.tx = new TransactionTemplate(manager);
                this.razorpayClient = razorpayClient;
                this.publicKeyId = publicKeyId;
        }

        public OrderResponse createOrder(
                        UUID userId,
                        UUID reservationId) {

                Preparation prepared = tx.execute(
                                status -> prepareOrder(userId, reservationId));

                if (prepared == null) {
                        throw new IllegalStateException(
                                        "Payment preparation failed");
                }

                LocalOrder order = prepared.order();

                // An existing provider order can be returned.
                // But a PENDING order without a provider ID
                // must be reconciled before another API call.
                if (!prepared.newlyCreated()) {

                        if (order.providerOrderId() == null) {
                                throw conflict(
                                                "Payment order is pending reconciliation");
                        }

                        return toResponse(userId, order);
                }

                // External network call is OUTSIDE a DB transaction.
                String receipt = order.id().toString();

                RazorpayOrderClient.ProviderOrder providerOrder = razorpayClient.createOrder(
                                order.amountPaise(),
                                receipt);

                // Save the provider ID even if the reservation
                // expired while Razorpay was responding.
                LocalOrder completed = tx.execute(status -> finalizeProviderOrder(
                                userId,
                                order.id(),
                                providerOrder.id()));

                if (completed == null) {
                        throw new IllegalStateException(
                                        "Provider order finalization failed");
                }

                return toResponse(userId, completed);
        }

        private Preparation prepareOrder(
                        UUID userId,
                        UUID reservationId) {

                verifyActiveUser(userId);

                List<UUID> ownerIds = jdbc.query("""
                                SELECT id
                                FROM reservations
                                WHERE id = ?
                                  AND user_id = ?
                                FOR UPDATE
                                """,
                                (rs, n) -> rs.getObject("id", UUID.class),
                                reservationId,
                                userId);

                if (ownerIds.isEmpty()) {
                        throw notFound();
                }

                List<LocalOrder> existing = jdbc.query("""
                                SELECT id, reservation_id, amount_paise,
                                       status, provider_order_id, currency
                                FROM payment_orders
                                WHERE reservation_id = ?
                                  AND user_id = ?
                                """,
                                this::mapOrder,
                                reservationId,
                                userId);

                if (!existing.isEmpty()) {
                        LocalOrder previous = existing.get(0);

                        if (!PROVIDER.equals(
                                        getProvider(previous.id()))) {
                                throw conflict(
                                                "Reservation already has a different payment provider");
                        }

                        return new Preparation(previous, false);
                }

                List<BigDecimal> prices = jdbc.query("""
                                SELECT total_price
                                FROM reservations
                                WHERE id = ?
                                  AND user_id = ?
                                  AND status = 'HELD'
                                  AND expires_at > clock_timestamp()
                                """,
                                (rs, n) -> rs.getBigDecimal("total_price"),
                                reservationId,
                                userId);

                if (prices.isEmpty()) {
                        throw conflict(
                                        "Reservation is not eligible for payment");
                }

                Integer totalSeats = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM reservation_seats
                                WHERE reservation_id = ?
                                """,
                                Integer.class,
                                reservationId);

                Integer heldSeats = jdbc.queryForObject("""
                                SELECT COUNT(*)
                                FROM reservation_seats rs
                                JOIN show_seats ss
                                  ON ss.id = rs.show_seat_id
                                 AND ss.show_id = rs.show_id
                                WHERE rs.reservation_id = ?
                                  AND ss.status = 'HELD'
                                  AND ss.hold_token = ?
                                  AND ss.hold_expires_at > clock_timestamp()
                                """,
                                Integer.class,
                                reservationId,
                                reservationId);

                if (totalSeats == null
                                || totalSeats == 0
                                || !totalSeats.equals(heldSeats)) {
                        throw conflict(
                                        "Reservation seat holds are not valid");
                }

                long amountPaise;

                try {
                        amountPaise = prices.get(0)
                                        .movePointRight(2)
                                        .longValueExact();
                } catch (ArithmeticException ex) {
                        throw new IllegalStateException(
                                        "Invalid reservation amount",
                                        ex);
                }

                if (amountPaise < 100) {
                        throw conflict(
                                        "Payment amount must be at least INR 1");
                }

                UUID id = UUID.randomUUID();

                jdbc.update("""
                                INSERT INTO payment_orders (
                                    id, reservation_id, user_id,
                                    provider, amount_paise,
                                    currency, status
                                )
                                VALUES (?, ?, ?, 'RAZORPAY', ?, 'INR', 'PENDING')
                                """,
                                id,
                                reservationId,
                                userId,
                                amountPaise);

                return new Preparation(
                                new LocalOrder(
                                                id,
                                                reservationId,
                                                amountPaise,
                                                "PENDING",
                                                null,
                                                "INR"),
                                true);
        }

        private LocalOrder finalizeProviderOrder(
                        UUID userId,
                        UUID orderId,
                        String providerOrderId) {

                List<UUID> reservations = jdbc.query("""
                                SELECT reservation_id
                                FROM payment_orders
                                WHERE id = ?
                                  AND user_id = ?
                                  AND provider = 'RAZORPAY'
                                """,
                                (rs, n) -> rs.getObject("reservation_id", UUID.class),
                                orderId,
                                userId);

                if (reservations.isEmpty()) {
                        throw notFound();
                }

                UUID reservationId = reservations.get(0);

                // Same lock order as cancellation and expiry.
                jdbc.query("""
                                SELECT id
                                FROM reservations
                                WHERE id = ?
                                FOR UPDATE
                                """,
                                (rs, n) -> rs.getObject("id", UUID.class),
                                reservationId);

                jdbc.query("""
                                SELECT id
                                FROM payment_orders
                                WHERE id = ?
                                FOR UPDATE
                                """,
                                (rs, n) -> rs.getObject("id", UUID.class),
                                orderId);

                Boolean stillActive = jdbc.queryForObject("""
                                SELECT (
                                    status = 'HELD'
                                    AND expires_at > clock_timestamp()
                                )
                                FROM reservations
                                WHERE id = ?
                                """,
                                Boolean.class,
                                reservationId);

                String newStatus = Boolean.TRUE.equals(stillActive)
                                ? "CREATED"
                                : "PENDING";

                int updated = jdbc.update("""
                                UPDATE payment_orders
                                SET provider_order_id = ?,
                                    status = ?,
                                    updated_at = clock_timestamp()
                                WHERE id = ?
                                  AND user_id = ?
                                  AND provider = 'RAZORPAY'
                                  AND provider_order_id IS NULL
                                  AND status = 'PENDING'
                                """,
                                providerOrderId,
                                newStatus,
                                orderId,
                                userId);

                if (updated != 1) {
                        throw new IllegalStateException(
                                        "Provider order could not be attached");
                }

                return getLocalOrder(userId, orderId);
        }

        public OrderResponse getOrder(
                        UUID userId,
                        UUID orderId) {

                verifyActiveUser(userId);

                return toResponse(
                                userId,
                                getLocalOrder(userId, orderId));
        }

        private OrderResponse toResponse(
                        UUID userId,
                        LocalOrder order) {

                Boolean eligible = jdbc.queryForObject("""
                                SELECT (
                                    status = 'HELD'
                                    AND expires_at > clock_timestamp()
                                )
                                FROM reservations
                                WHERE id = ?
                                  AND user_id = ?
                                """,
                                Boolean.class,
                                order.reservationId(),
                                userId);

                return new OrderResponse(
                                order.id(),
                                order.reservationId(),
                                order.providerOrderId(),
                                order.amountPaise(),
                                order.currency(),
                                order.status(),
                                publicKeyId,
                                "CREATED".equals(order.status())
                                                && order.providerOrderId() != null
                                                && Boolean.TRUE.equals(eligible));
        }

        private LocalOrder getLocalOrder(
                        UUID userId,
                        UUID orderId) {

                List<LocalOrder> rows = jdbc.query("""
                                SELECT id, reservation_id, amount_paise,
                                       status, provider_order_id, currency
                                FROM payment_orders
                                WHERE id = ?
                                  AND user_id = ?
                                  AND provider = 'RAZORPAY'
                                """,
                                this::mapOrder,
                                orderId,
                                userId);

                if (rows.isEmpty()) {
                        throw notFound();
                }

                return rows.get(0);
        }

        private LocalOrder mapOrder(
                        java.sql.ResultSet rs,
                        int rowNum) throws java.sql.SQLException {

                return new LocalOrder(
                                rs.getObject("id", UUID.class),
                                rs.getObject("reservation_id", UUID.class),
                                rs.getLong("amount_paise"),
                                rs.getString("status"),
                                rs.getString("provider_order_id"),
                                rs.getString("currency"));
        }

        private String getProvider(UUID orderId) {
                return jdbc.queryForObject("""
                                SELECT provider
                                FROM payment_orders
                                WHERE id = ?
                                """,
                                String.class,
                                orderId);
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
                                        "Account unavailable");
                }
        }

        private ResponseStatusException conflict(String message) {
                return new ResponseStatusException(
                                HttpStatus.CONFLICT, message);
        }

        private ResponseStatusException notFound() {
                return new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Payment order not found");
        }
}
