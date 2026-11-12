
package com.flashtix.controller;

import com.flashtix.dto.CreatePaymentOrderRequest;
import com.flashtix.dto.PaymentOrderResponse;
import com.flashtix.service.MockPaymentService;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile("dev")
@RequestMapping("/api/payments/orders")
public class PaymentOrderController {

    private final MockPaymentService service;

    public PaymentOrderController(
            MockPaymentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentOrderResponse createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePaymentOrderRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return service.createOrder(
                userId,
                request.reservationId());
    }

    @GetMapping("/{orderId}")
    public PaymentOrderResponse getOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return service.getOrder(userId, orderId);
    }
}
