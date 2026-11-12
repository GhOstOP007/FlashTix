
package com.flashtix.dto;

import java.math.BigDecimal;

public record ShowSeatResponse(
        Long id,
        Long seatId,
        String rowLabel,
        Integer seatNumber,
        String seatType,
        BigDecimal price,
        String status) {
}
