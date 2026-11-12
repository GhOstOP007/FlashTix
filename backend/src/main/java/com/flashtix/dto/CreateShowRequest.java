
package com.flashtix.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public record CreateShowRequest(

        @NotNull Long eventId,

        @NotNull Long venueId,

        @NotNull @Future Instant startTime,

        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal ticketPrice) {
}
