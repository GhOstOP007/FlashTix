
package com.flashtix.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

public record MultiSeatHoldResponse(
        Long showId,
        List<Long> showSeatIds,
        UUID holdToken,
        String status,
        Instant expiresAt,
        BigDecimal totalPrice) {
}
