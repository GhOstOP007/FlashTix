
package com.flashtix.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentOrderResponse(
        UUID orderId,
        UUID reservationId,
        long amountPaise,
        String currency,
        String provider,
        String status,
        Instant createdAt) {
}
