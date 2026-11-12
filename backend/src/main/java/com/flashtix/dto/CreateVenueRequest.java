
package com.flashtix.dto;

import jakarta.validation.constraints.*;

public record CreateVenueRequest(
        @NotBlank @Size(max = 200) String name,

        @NotBlank @Size(max = 100) String city,

        String address,

        @NotNull @Min(1) @Max(26) Integer rows,

        @NotNull @Min(1) @Max(50) Integer seatsPerRow) {
}
