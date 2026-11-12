
package com.flashtix.service;

import com.flashtix.service.PaymentReconciliationRules.Decision;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
public class RazorpayReconciliationService {

    private final JdbcTemplate jdbc;
    private final RazorpayReconciliationClient client;

    public RazorpayReconciliationService(
            JdbcTemplate jdbc,
            RazorpayReconciliationClient client) {

        this.jdbc = jdbc;
        this.client = client;
    }

    private record LocalOrder(
            UUID id,
            UUID reservationId,
            String providerOrderId,
            long amountPaise,
            String currency,
            String localOrderStatus,
            String reservationStatus,
            Instant reservationExpiresAt) {
    }

    public record ReconciliationReport(
            UUID orderId,
            UUID reservationId,
            String providerOrderId,
            String providerOrderStatus,
            String localOrderStatus,
            String reservationStatus,
            long expectedAmountPaise,
            int capturedPaymentCount,
            Decision decision,
            String message) {
    }

    public ReconciliationReport reconcile(
            UUID userId,
            UUID orderId) {

        LocalOrder local = findOwnedOrder(userId, orderId);

        if (local.providerOrderId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Provider order has not been attached. "
                            + "Reconciliation by receipt is required.");
        }

        // Both calls use server-side Razorpay credentials.
        var providerOrder = client.fetchOrder(
                local.providerOrderId());

        var payments = client.fetchPayments(
                local.providerOrderId());

        boolean mismatch = false;
        int capturedCount = 0;

        // Check the provider order against local records.
        if (!providerOrder.id().equals(
                local.providerOrderId())
                || !providerOrder.receipt().equals(
                        local.id().toString())
                || providerOrder.amount() != local.amountPaise()
                || !providerOrder.currency().equals(
                        local.currency())) {

            mismatch = true;
        }

        if (!List.of("created", "attempted", "paid")
                .contains(providerOrder.status())) {
            mismatch = true;
        }

        for (var payment : payments) {

            if (!payment.orderId().equals(
                    local.providerOrderId())
                    || payment.amount() != local.amountPaise()
                    || !payment.currency().equals(
                            local.currency())) {

                mismatch = true;
            }

            if (!List.of(
                    "created",
                    "authorized",
                    "captured",
                    "refunded",
                    "failed").contains(payment.status())) {
                mismatch = true;
            }

            // Refunded payments require separate
            // reconciliation and must not be fulfilled.
            if ("refunded".equals(payment.status())
                    || payment.amountRefunded() != 0) {
                mismatch = true;
            }

            if ("captured".equals(payment.status())) {
                capturedCount++;
            }
        }

        if (capturedCount > 1) {
            mismatch = true;
        }

        if ("paid".equals(providerOrder.status())
                && capturedCount == 0) {
            mismatch = true;
        }

        if (capturedCount > 0
                && !"paid".equals(providerOrder.status())) {
            mismatch = true;
        }

        boolean reservationActive = "HELD".equals(local.reservationStatus())
                && local.reservationExpiresAt()
                        .isAfter(Instant.now());

        Decision decision = PaymentReconciliationRules.classify(
                local.reservationStatus(),
                reservationActive,
                local.localOrderStatus(),
                providerOrder.status(),
                capturedCount > 0,
                mismatch);

        return new ReconciliationReport(
                local.id(),
                local.reservationId(),
                local.providerOrderId(),
                providerOrder.status(),
                local.localOrderStatus(),
                local.reservationStatus(),
                local.amountPaise(),
                capturedCount,
                decision,
                explain(decision));
    }

    private LocalOrder findOwnedOrder(
            UUID userId,
            UUID orderId) {

        List<LocalOrder> rows = jdbc.query("""
                SELECT
                    p.id,
                    p.reservation_id,
                    p.provider_order_id,
                    p.amount_paise,
                    p.currency,
                    p.status AS order_status,
                    r.status AS reservation_status,
                    r.expires_at
                FROM payment_orders p
                JOIN reservations r
                  ON r.id = p.reservation_id
                 AND r.user_id = p.user_id
                JOIN users u
                  ON u.id = p.user_id
                WHERE p.id = ?
                  AND p.user_id = ?
                  AND p.provider = 'RAZORPAY'
                  AND u.enabled = TRUE
                """,
                (rs, n) -> new LocalOrder(
                        rs.getObject("id", UUID.class),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getString("provider_order_id"),
                        rs.getLong("amount_paise"),
                        rs.getString("currency"),
                        rs.getString("order_status"),
                        rs.getString("reservation_status"),
                        rs.getTimestamp("expires_at").toInstant()),
                orderId,
                userId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payment order not found");
        }

        return rows.get(0);
    }

    private String explain(Decision decision) {

        return switch (decision) {
            case UNPAID ->
                "No captured payment was found.";

            case PAYMENT_PENDING ->
                "A payment was attempted but capture "
                        + "has not been established.";

            case CAPTURED_AWAITING_FULFILLMENT ->
                "Captured payment found. Server-side "
                        + "fulfillment checks are still required.";

            case LATE_CAPTURE_REVIEW ->
                "Payment was captured but the reservation "
                        + "is not eligible. Review compensation.";

            case ALREADY_FULFILLED ->
                "Provider evidence and local confirmation agree.";

            case REQUIRES_INVESTIGATION ->
                "Payment evidence or local state is inconsistent.";
        };
    }
}
