
package com.flashtix.dto;

public record VenueResponse(
        Long id,
        String name,
        String city,
        String address,
        long totalSeats) {
}
