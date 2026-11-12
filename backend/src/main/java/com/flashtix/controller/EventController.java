
package com.flashtix.controller;

import com.flashtix.dto.CreateEventRequest;
import com.flashtix.dto.EventResponse;
import com.flashtix.service.EventService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
            @Valid @RequestBody CreateEventRequest request) {

        EventResponse event = service.createEvent(request);

        return ResponseEntity
                .created(URI.create("/api/events/" + event.id()))
                .body(event);
    }

    @GetMapping
    public List<EventResponse> getUpcomingEvents() {
        return service.getUpcomingEvents();
    }

    @GetMapping("/{id}")
    public EventResponse getEvent(@PathVariable Long id) {
        return service.getEvent(id);
    }
}
