
package com.flashtix.controller;

import com.flashtix.dto.*;
import com.flashtix.service.VenueService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @PostMapping
    public ResponseEntity<VenueResponse> createVenue(
            @Valid @RequestBody CreateVenueRequest request) {
        VenueResponse venue = venueService.createVenue(request);

        return ResponseEntity
                .created(
                        URI.create("/api/venues/" + venue.id()))
                .body(venue);
    }

    @GetMapping
    public List<VenueResponse> getAllVenues() {
        return venueService.getAllVenues();
    }

    @GetMapping("/{venueId}/seats")
    public List<SeatResponse> getVenueSeats(
            @PathVariable Long venueId) {
        return venueService.getVenueSeats(venueId);
    }
}
