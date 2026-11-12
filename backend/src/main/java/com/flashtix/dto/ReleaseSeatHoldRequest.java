
package com.flashtix.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReleaseSeatHoldRequest(
        @NotNull UUID holdToken) {
}
