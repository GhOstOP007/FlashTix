
package com.flashtix.controller;

import com.flashtix.service.PaymentFulfillmentEngine;
import com.flashtix.service.RazorpayVerifiedFulfillmentService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
// import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
@RequestMapping("/api/dev/razorpay/orders")
public class RazorpayFulfillmentController {

    private final RazorpayVerifiedFulfillmentService service;

    public RazorpayFulfillmentController(
            RazorpayVerifiedFulfillmentService service) {
        this.service = service;
    }

    @PostMapping("/{orderId}/fulfill")
    public PaymentFulfillmentEngine.FulfillmentResult fulfill(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return service.fulfill(userId, orderId);
    }
}
