
package com.flashtix.service;

import com.flashtix.dto.CreateEventRequest;
import com.flashtix.dto.EventResponse;
import com.flashtix.entity.Event;
import com.flashtix.repository.EventRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class EventService {

    private final EventRepository repository;

    public EventService(EventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public EventResponse createEvent(
            CreateEventRequest request) {

        Event event = new Event(
            request.title(),
            request.description(),
            request.category(),
            request.startTime(),
            request.venueName(),
            request.city()
        );

        Event saved = repository.saveAndFlush(event);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getUpcomingEvents() {

        return repository
            .findByStartTimeAfterOrderByStartTimeAsc(
                Instant.now()
            )
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(Long id) {

        Event event = repository.findById(id)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Event not found"
                )
            );

        return toResponse(event);
    }

    private EventResponse toResponse(Event event) {
        return new EventResponse(
            event.getId(),
            event.getTitle(),
            event.getDescription(),
            event.getCategory(),
            event.getStartTime(),
            event.getVenueName(),
            event.getCity(),
            event.getCreatedAt()
        );
    }
}
