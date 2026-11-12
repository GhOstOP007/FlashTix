
package com.flashtix.dto;

import java.time.Instant;
import java.util.UUID;

public record SeatHoldResponse(
        Long showId,
        Long showSeatId,
        UUID holdToken,
        String status,
        Instant expiresAt) {
}
