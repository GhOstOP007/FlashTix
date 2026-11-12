
package com.flashtix.controller;

import com.flashtix.service.PaymentFulfillmentEngine;
import com.flashtix.service.RazorpayCheckoutSignatureService;
import com.flashtix.service.RazorpayVerifiedFulfillmentService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
@RequestMapping("/api/dev/razorpay/orders")
public class RazorpayCheckoutController {

        private final JdbcTemplate jdbc;
        private final RazorpayCheckoutSignatureService verifier;
        private final RazorpayVerifiedFulfillmentService fulfillment;

        public RazorpayCheckoutController(
                        JdbcTemplate jdbc,
                        RazorpayCheckoutSignatureService verifier,
                        RazorpayVerifiedFulfillmentService fulfillment) {

                this.jdbc = jdbc;
                this.verifier = verifier;
                this.fulfillment = fulfillment;
        }

        public record VerifyCheckoutRequest(
                        String razorpay_payment_id,
                        String razorpay_order_id,
                        String razorpay_signature) {
        }

        @PostMapping("/{orderId}/verify-checkout")
        public PaymentFulfillmentEngine.FulfillmentResult verify(
                        @AuthenticationPrincipal Jwt jwt,
                        @PathVariable UUID orderId,
                        @RequestBody VerifyCheckoutRequest request) {

                UUID userId = UUID.fromString(
                                jwt.getSubject());

                if (request == null) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Checkout response is required");
                }

                // Resolve the order from our own database.
                // Never trust a provider order ID alone.
                List<String> providerOrderIds = jdbc.query("""
                                SELECT p.provider_order_id
                                FROM payment_orders p
                                JOIN users u ON u.id = p.user_id
                                WHERE p.id = ?
                                  AND p.user_id = ?
                                  AND p.provider = 'RAZORPAY'
                                  AND u.enabled = TRUE
                                  AND p.provider_order_id IS NOT NULL
                                """,
                                (rs, n) -> rs.getString("provider_order_id"),
                                orderId,
                                userId);

                if (providerOrderIds.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Payment order not found");
                }

                String expectedProviderOrderId = providerOrderIds.get(0);

                if (!expectedProviderOrderId.equals(
                                request.razorpay_order_id())) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Checkout order ID mismatch");
                }

                // Prove Checkout callback authenticity.
                verifier.verify(
                                expectedProviderOrderId,
                                request.razorpay_payment_id(),
                                request.razorpay_signature());

                // Independently fetch Razorpay's payment state.
                // Existing fulfilment code requires CAPTURED,
                // checks amount and currency, and locks rows.
                return fulfillment.fulfill(
                                userId,
                                orderId,
                                request.razorpay_payment_id());
        }
}
