
package com.flashtix.dto;

public record SeatResponse(
        Long id,
        String rowLabel,
        Integer seatNumber,
        String seatType) {
}
