
package com.flashtix.repository;

import com.flashtix.entity.ShowSeat;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

import java.util.List;

public interface ShowSeatRepository
        extends JpaRepository<ShowSeat, Long> {

    List<ShowSeat> findByShowId(Long showId);

    long countByShowId(Long showId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE show_seats
            SET
                status = 'HELD',
                hold_token = :token,
                hold_expires_at =
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes'
            WHERE id = :seatId
              AND show_id = :showId
              AND (
                  status = 'AVAILABLE'
                  OR (
                      status = 'HELD'
                      AND hold_expires_at <= CURRENT_TIMESTAMP
                  )
              )
            """, nativeQuery = true)
    int acquireSeatHold(
            @Param("showId") Long showId,
            @Param("seatId") Long seatId,
            @Param("token") UUID token);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE show_seats
            SET status = 'AVAILABLE',
                hold_token = NULL,
                hold_expires_at = NULL
            WHERE id = :seatId
              AND show_id = :showId
              AND status = 'HELD'
              AND hold_token = :token
              AND hold_expires_at > CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int releaseSeatHold(
            @Param("showId") Long showId,
            @Param("seatId") Long seatId,
            @Param("token") UUID token);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE show_seats
            SET status = 'AVAILABLE',
                hold_token = NULL,
                hold_expires_at = NULL
            WHERE status = 'HELD'
              AND hold_expires_at <= CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int releaseExpiredHolds();

}
