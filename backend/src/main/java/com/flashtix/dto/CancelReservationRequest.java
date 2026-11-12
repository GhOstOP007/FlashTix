
package com.flashtix.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CancelReservationRequest(

        @NotNull UUID clientSessionId

) {
}
