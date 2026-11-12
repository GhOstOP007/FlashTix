
package com.flashtix.dto;

import java.time.Instant;

public record ShowResponse(
        Long id,
        Long eventId,
        Long venueId,
        Instant startTime,
        String status,
        long totalSeats) {
}
