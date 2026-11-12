
package com.flashtix.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(

        UUID reservationId,

        Long showId,

        List<Long> showSeatIds,

        String status,

        BigDecimal totalPrice,

        Instant expiresAt,

        Instant createdAt

) {
}
