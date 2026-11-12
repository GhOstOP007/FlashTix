
package com.flashtix.repository;

import com.flashtix.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeatRepository
        extends JpaRepository<Seat, Long> {

    List<Seat> findByVenue_IdOrderByRowLabelAscSeatNumberAsc(
            Long venueId);

    long countByVenue_Id(Long venueId);
}
