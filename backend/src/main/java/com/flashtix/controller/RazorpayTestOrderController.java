
package com.flashtix.controller;

import com.flashtix.dto.CreatePaymentOrderRequest;
import com.flashtix.service.RazorpayTestOrderService;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile("dev")
@ConditionalOnProperty(
    name = "flashtix.razorpay.orders-enabled",
    havingValue = "true"
)
@RequestMapping("/api/dev/razorpay/orders")
public class RazorpayTestOrderController {

    private final RazorpayTestOrderService service;

    public RazorpayTestOrderController(
            RazorpayTestOrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RazorpayTestOrderService.OrderResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePaymentOrderRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return service.createOrder(
            userId,
            request.reservationId()
        );
    }

    @GetMapping("/{orderId}")
    public RazorpayTestOrderService.OrderResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return service.getOrder(userId, orderId);
    }
}
