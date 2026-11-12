
package com.flashtix.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record AuthenticatedReservationRequest(

        @NotNull UUID idempotencyKey,

        @NotNull Long showId,

        @NotEmpty @Size(min = 1, max = 6) List<@NotNull Long> showSeatIds

) {
}
