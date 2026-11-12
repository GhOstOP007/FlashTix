
package com.flashtix.controller;

import com.flashtix.dto.PaymentOrderResponse;
import com.flashtix.dto.SimulatePaymentRequest;
import com.flashtix.service.MockPaymentService;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile("dev")
@RequestMapping("/api/dev/payments")
public class MockPaymentController {

    private final MockPaymentService service;

    public MockPaymentController(
            MockPaymentService service) {
        this.service = service;
    }

    @PostMapping("/{orderId}/simulate")
    public PaymentOrderResponse simulate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @Valid @RequestBody SimulatePaymentRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return service.simulatePayment(
                userId,
                orderId,
                request.outcome());
    }
}
