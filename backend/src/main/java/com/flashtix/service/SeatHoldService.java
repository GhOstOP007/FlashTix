
package com.flashtix.service;

import com.flashtix.dto.SeatHoldResponse;
import com.flashtix.entity.EventShow;
import com.flashtix.entity.ShowSeat;
import com.flashtix.repository.EventShowRepository;
import com.flashtix.repository.ShowSeatRepository;
import com.flashtix.dto.SeatReleaseResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.flashtix.dto.MultiSeatHoldRequest;
import com.flashtix.dto.MultiSeatHoldResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;

@Service
public class SeatHoldService {

        private final ShowSeatRepository seatRepository;
        private final EventShowRepository showRepository;

        public SeatHoldService(
                        ShowSeatRepository seatRepository,
                        EventShowRepository showRepository) {

                this.seatRepository = seatRepository;
                this.showRepository = showRepository;
        }

        @Transactional
        public SeatHoldResponse holdSeat(
                        Long showId,
                        Long showSeatId) {

                EventShow show = showRepository
                                .findById(showId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Show not found"));

                if (!"SCHEDULED".equals(show.getStatus())
                                || !show.getStartTime().isAfter(Instant.now())) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Show is not available for booking");
                }

                UUID token = UUID.randomUUID();

                int updatedRows = seatRepository.acquireSeatHold(
                                showId,
                                showSeatId,
                                token);

                if (updatedRows == 0) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Seat is already held, booked, or unavailable");
                }

                ShowSeat seat = seatRepository
                                .findById(showSeatId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Seat not found"));

                return new SeatHoldResponse(
                                showId,
                                showSeatId,
                                token,
                                seat.getStatus(),
                                seat.getHoldExpiresAt());
        }

        @Transactional
        public SeatReleaseResponse releaseSeat(
                        Long showId,
                        Long showSeatId,
                        UUID holdToken) {

                int updatedRows = seatRepository.releaseSeatHold(
                                showId,
                                showSeatId,
                                holdToken);

                if (updatedRows == 0) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Hold is invalid, expired, or no longer owned");
                }

                return new SeatReleaseResponse(
                                showId,
                                showSeatId,
                                "AVAILABLE",
                                "Seat hold released successfully");
        }

        @Transactional
        public void cleanupExpiredHolds() {

                int released = seatRepository.releaseExpiredHolds();

                if (released > 0) {
                        System.out.println(
                                        "[FlashTix] Released " + released
                                                        + " expired seat holds");
                }
        }

        @Transactional
        public MultiSeatHoldResponse holdMultipleSeats(
                        Long showId,
                        MultiSeatHoldRequest request) {

                // 1. Validate the selected seat IDs
                List<Long> requestedIds = request.showSeatIds();

                if (requestedIds == null
                                || requestedIds.isEmpty()
                                || requestedIds.size() > 6
                                || requestedIds.stream().anyMatch(
                                                id -> id == null || id <= 0)) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Select between 1 and 6 valid seats");
                }

                // 2. Reject duplicate seat IDs
                Set<Long> uniqueIds = new HashSet<>(requestedIds);

                if (uniqueIds.size() != requestedIds.size()) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Duplicate seat IDs are not allowed");
                }

                // 3. Verify the show
                EventShow show = showRepository
                                .findById(showId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Show not found"));

                if (!"SCHEDULED".equals(show.getStatus())
                                || !show.getStartTime().isAfter(Instant.now())) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Show is not available for booking");
                }

                // 4. Sort IDs to reduce deadlock risk
                List<Long> sortedIds = new ArrayList<>(uniqueIds);
                sortedIds.sort(Long::compareTo);

                // 5. Generate one token for the entire group
                UUID holdToken = UUID.randomUUID();

                // 6. Try to hold every seat atomically
                for (Long seatId : sortedIds) {

                        int updatedRows = seatRepository.acquireSeatHold(
                                        showId,
                                        seatId,
                                        holdToken);

                        if (updatedRows != 1) {
                                throw new ResponseStatusException(
                                                HttpStatus.CONFLICT,
                                                "One or more seats are unavailable");
                        }
                }

                // 7. Read the successfully held seats
                List<ShowSeat> heldSeats = seatRepository.findAllById(sortedIds);

                if (heldSeats.size() != sortedIds.size()
                                || heldSeats.stream().anyMatch(
                                                seat -> !holdToken.equals(
                                                                seat.getHoldToken()))) {

                        throw new IllegalStateException(
                                        "Reservation verification failed");
                }

                // 8. Calculate total price from the database
                BigDecimal totalPrice = heldSeats.stream()
                                .map(ShowSeat::getPrice)
                                .reduce(
                                                BigDecimal.ZERO,
                                                BigDecimal::add);

                // 9. Use the earliest expiration in the group
                Instant expiresAt = heldSeats.stream()
                                .map(ShowSeat::getHoldExpiresAt)
                                .min(Instant::compareTo)
                                .orElseThrow();

                // 10. Return the completed reservation
                return new MultiSeatHoldResponse(
                                showId,
                                sortedIds,
                                holdToken,
                                "HELD",
                                expiresAt,
                                totalPrice);
        }

}
