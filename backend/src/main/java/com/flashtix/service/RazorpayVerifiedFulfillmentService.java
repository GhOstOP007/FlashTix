
package com.flashtix.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
public class RazorpayVerifiedFulfillmentService {

        private final JdbcTemplate jdbc;
        private final RazorpayReconciliationClient client;
        private final PaymentFulfillmentEngine engine;

        public RazorpayVerifiedFulfillmentService(
                        JdbcTemplate jdbc,
                        RazorpayReconciliationClient client,
                        PaymentFulfillmentEngine engine) {

                this.jdbc = jdbc;
                this.client = client;
                this.engine = engine;
        }

        private record OrderReference(
                        String providerOrderId,
                        long amountPaise,
                        String currency,
                        String receipt) {
        }

        /**
         * General server-side fulfilment.
         *
         * Used by the existing fulfilment controller.
         *
         * Payment evidence is independently fetched
         * from Razorpay before updating the database.
         */
        public PaymentFulfillmentEngine.FulfillmentResult fulfill(
                        UUID userId,
                        UUID orderId) {

                return fulfill(userId, orderId, null);
        }

        /**
         * Checkout-specific fulfilment.
         *
         * expectedPaymentId must come from a Checkout
         * response whose HMAC signature has already
         * been verified by the backend.
         */
        public PaymentFulfillmentEngine.FulfillmentResult fulfill(
                        UUID userId,
                        UUID orderId,
                        String expectedPaymentId) {

                if (userId == null || orderId == null) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "User ID and payment order ID are required");
                }

                if (expectedPaymentId != null
                                && (expectedPaymentId.isBlank()
                                                || !expectedPaymentId.startsWith("pay_"))) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Invalid expected Razorpay payment ID");
                }

                // =============================================
                // 1. LOAD THE AUTHENTICATED USER'S PAYMENT ORDER
                // =============================================

                List<OrderReference> matches = jdbc.query("""
                                SELECT
                                    p.provider_order_id,
                                    p.amount_paise,
                                    p.currency,
                                    CAST(p.id AS TEXT) AS receipt
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
                                (rs, n) -> new OrderReference(
                                                rs.getString("provider_order_id"),
                                                rs.getLong("amount_paise"),
                                                rs.getString("currency"),
                                                rs.getString("receipt")),
                                orderId,
                                userId);

                if (matches.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Payment order not found");
                }

                OrderReference expected = matches.get(0);

                if (expected.providerOrderId() == null
                                || expected.providerOrderId().isBlank()) {

                        throw conflict(
                                        "Provider order requires reconciliation");
                }

                // =============================================
                // 2. FETCH FRESH PROVIDER EVIDENCE
                // =============================================

                // External HTTP requests occur before entering
                // the transactional fulfilment engine.
                var providerOrder = client.fetchOrder(
                                expected.providerOrderId());

                var payments = client.fetchPayments(
                                expected.providerOrderId());

                // =============================================
                // 3. VERIFY THE RAZORPAY ORDER
                // =============================================

                if (!expected.providerOrderId().equals(
                                providerOrder.id())
                                || !expected.receipt().equals(
                                                providerOrder.receipt())
                                || expected.amountPaise() != providerOrder.amount()
                                || !expected.currency().equals(
                                                providerOrder.currency())) {

                        throw conflict(
                                        "Razorpay order evidence mismatch");
                }

                if (!"paid".equals(providerOrder.status())) {
                        throw conflict(
                                        "Provider order is not paid");
                }

                // =============================================
                // 4. VERIFY EVERY RETURNED PAYMENT
                // =============================================

                for (var payment : payments) {

                        if (!expected.providerOrderId().equals(
                                        payment.orderId())
                                        || expected.amountPaise() != payment.amount()
                                        || !expected.currency().equals(
                                                        payment.currency())) {

                                throw conflict(
                                                "Razorpay payment evidence mismatch");
                        }

                        // Never fulfil when the provider indicates
                        // that a payment has been refunded or
                        // partially refunded.
                        if ("refunded".equals(payment.status())
                                        || payment.amountRefunded() != 0) {

                                throw conflict(
                                                "Refunded payments require investigation");
                        }
                }

                // =============================================
                // 5. REQUIRE EXACTLY ONE CAPTURED PAYMENT
                // =============================================

                List<RazorpayReconciliationClient.ProviderPayment> captured = payments.stream()
                                .filter(payment -> "captured".equals(payment.status()))
                                .toList();

                if (captured.size() != 1) {
                        throw conflict(
                                        "Exactly one captured payment is required");
                }

                var payment = captured.get(0);

                if (payment.id() == null
                                || !payment.id().startsWith("pay_")) {

                        throw conflict(
                                        "Invalid provider payment ID");
                }

                // =============================================
                // 6. VERIFY THE CHECKOUT PAYMENT ID
                // =============================================

                // This check is active when the request comes
                // from the signed Checkout verification flow.
                //
                // A successful capture on the same order is
                // not sufficient if its payment ID differs
                // from the signed Checkout response.
                if (expectedPaymentId != null
                                && !expectedPaymentId.equals(
                                                payment.id())) {

                        throw conflict(
                                        "Captured payment does not match Checkout");
                }

                // =============================================
                // 7. BUILD VERIFIED PAYMENT EVIDENCE
                // =============================================

                var evidence = new PaymentFulfillmentEngine.VerifiedCapture(
                                providerOrder.id(),
                                payment.id(),
                                providerOrder.receipt(),
                                payment.amount(),
                                payment.currency());

                // =============================================
                // 8. EXECUTE ATOMIC DATABASE FULFILMENT
                // =============================================

                // The engine locks the reservation and payment
                // order before updating seats and payment status.
                //
                // Successful fulfilment:
                // reservations.status = CONFIRMED
                // show_seats.status = BOOKED
                // payment_orders.status = SUCCEEDED
                //
                // Expired or cancelled reservations:
                // compensation case may be required.
                return engine.applyVerifiedCapture(
                                userId,
                                orderId,
                                evidence);
        }

        private ResponseStatusException conflict(
                        String message) {

                return new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                message);
        }
}
