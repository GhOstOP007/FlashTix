
package com.flashtix.dto;

public record SeatReleaseResponse(
        Long showId,
        Long showSeatId,
        String status,
        String message) {
}
