
package com.flashtix.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "reservation_seats")
@IdClass(ReservationSeatId.class)
public class ReservationSeat {

    @Id
    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Id
    @Column(name = "show_seat_id", nullable = false)
    private Long showSeatId;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "price_at_reservation", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtReservation;

    protected ReservationSeat() {
    }

    public ReservationSeat(
            UUID reservationId,
            Long showId,
            Long showSeatId,
            BigDecimal priceAtReservation) {

        this.reservationId = reservationId;
        this.showId = showId;
        this.showSeatId = showSeatId;
        this.priceAtReservation = priceAtReservation;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Long getShowId() {
        return showId;
    }

    public Long getShowSeatId() {
        return showSeatId;
    }

    public BigDecimal getPriceAtReservation() {
        return priceAtReservation;
    }
}
