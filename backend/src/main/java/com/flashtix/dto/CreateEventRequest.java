package com.flashtix.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateEventRequest(

        @NotBlank @Size(max = 200) String title,

        String description,

        @NotBlank @Size(max = 50) String category,

        @NotNull @Future Instant startTime,

        @NotBlank @Size(max = 200) String venueName,

        @NotBlank @Size(max = 100) String city) {
}
