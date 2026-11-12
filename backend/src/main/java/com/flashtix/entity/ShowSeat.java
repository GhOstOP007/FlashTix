
package com.flashtix.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "show_seats")
public class ShowSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "hold_token")
    private UUID holdToken;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    protected ShowSeat() {
    }

    public ShowSeat(
            Long showId,
            Long venueId,
            Long seatId,
            BigDecimal price) {
        this.showId = showId;
        this.venueId = venueId;
        this.seatId = seatId;
        this.price = price;
        this.status = "AVAILABLE";
    }

    public Long getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public Long getVenueId() {
        return venueId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getStatus() {
        return status;
    }

    public UUID getHoldToken() {
        return holdToken;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

}
