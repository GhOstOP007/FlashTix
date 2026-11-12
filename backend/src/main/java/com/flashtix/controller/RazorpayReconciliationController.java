
package com.flashtix.controller;

import com.flashtix.service.RazorpayReconciliationService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
@RequestMapping("/api/dev/razorpay/orders")
public class RazorpayReconciliationController {

    private final RazorpayReconciliationService service;

    public RazorpayReconciliationController(
            RazorpayReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/{orderId}/reconcile")
    public RazorpayReconciliationService.ReconciliationReport reconcile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return service.reconcile(userId, orderId);
    }
}
