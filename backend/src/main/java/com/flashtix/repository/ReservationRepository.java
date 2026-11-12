
package com.flashtix.repository;

import com.flashtix.entity.Reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository
        extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByClientSessionIdAndIdempotencyKey(
            UUID clientSessionId,
            UUID idempotencyKey);

    List<Reservation> findByClientSessionIdOrderByCreatedAtDesc(
            UUID clientSessionId);
}
