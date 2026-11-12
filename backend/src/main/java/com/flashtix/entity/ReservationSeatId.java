
package com.flashtix.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ReservationSeatId implements Serializable {

    private UUID reservationId;
    private Long showSeatId;

    public ReservationSeatId() {
    }

    public ReservationSeatId(
            UUID reservationId,
            Long showSeatId) {

        this.reservationId = reservationId;
        this.showSeatId = showSeatId;
    }

    @Override
    public boolean equals(Object obj) {

        if (this == obj) {
            return true;
        }

        if (!(obj instanceof ReservationSeatId other)) {
            return false;
        }

        return Objects.equals(
                reservationId,
                other.reservationId)
                && Objects.equals(
                        showSeatId,
                        other.showSeatId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                reservationId,
                showSeatId);
    }
}
