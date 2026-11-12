
package com.flashtix.controller;

import com.flashtix.dto.*;
import com.flashtix.service.ShowService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> createShow(
            @Valid @RequestBody CreateShowRequest request) {

        ShowResponse show = showService.createShow(request);

        return ResponseEntity
                .created(URI.create("/api/shows/" + show.id()))
                .body(show);
    }

    @GetMapping("/{id}")
    public ShowResponse getShow(@PathVariable Long id) {
        return showService.getShow(id);
    }

    @GetMapping("/{id}/seats")
    public List<ShowSeatResponse> getShowSeats(
            @PathVariable Long id) {
        return showService.getShowSeats(id);
    }

    @GetMapping
    public List<ShowResponse> getShowsForEvent(
            @RequestParam Long eventId) {

        return showService.getShowsForEvent(eventId);
    }

}
