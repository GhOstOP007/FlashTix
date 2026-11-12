package com.flashtix.repository;

import com.flashtix.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface EventRepository
        extends JpaRepository<Event, Long> {

    List<Event> findByStartTimeAfterOrderByStartTimeAsc(
            Instant time);
}
