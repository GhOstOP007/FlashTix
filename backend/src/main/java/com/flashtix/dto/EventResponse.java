package com.flashtix.dto;

import java.time.Instant;

public record EventResponse(
        Long id,
        String title,
        String description,
        String category,
        Instant startTime,
        String venueName,
        String city,
        Instant createdAt) {
}
