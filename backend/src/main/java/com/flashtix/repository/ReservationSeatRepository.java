
package com.flashtix.repository;

import com.flashtix.entity.ReservationSeat;
import com.flashtix.entity.ReservationSeatId;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationSeatRepository
        extends JpaRepository<ReservationSeat, ReservationSeatId> {

    List<ReservationSeat> findByReservationId(
            UUID reservationId);
}
