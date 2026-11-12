
package com.flashtix.repository;

import com.flashtix.entity.EventShow;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventShowRepository
        extends JpaRepository<EventShow, Long> {

    List<EventShow> findByEventIdAndStartTimeAfterAndStatusOrderByStartTimeAsc(
            Long eventId,
            Instant time,
            String status);
}
