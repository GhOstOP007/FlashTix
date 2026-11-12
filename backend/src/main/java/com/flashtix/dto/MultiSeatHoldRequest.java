
package com.flashtix.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record MultiSeatHoldRequest(

        @NotEmpty(message = "Select at least one seat") @Size(max = 6, message = "Maximum 6 seats allowed") List<@NotNull Long> showSeatIds

) {
}
