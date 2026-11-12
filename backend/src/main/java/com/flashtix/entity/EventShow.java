
package com.flashtix.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "event_shows")
public class EventShow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(nullable = false, length = 30)
    private String status;

    protected EventShow() {
    }

    public EventShow(
            Long eventId,
            Long venueId,
            Instant startTime) {
        this.eventId = eventId;
        this.venueId = venueId;
        this.startTime = startTime;
        this.status = "SCHEDULED";
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getVenueId() {
        return venueId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public String getStatus() {
        return status;
    }
}
