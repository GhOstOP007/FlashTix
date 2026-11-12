
package com.flashtix.service;

import com.flashtix.dto.*;
import com.flashtix.entity.*;
import com.flashtix.repository.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ShowService {

        private final EventRepository eventRepository;
        private final VenueRepository venueRepository;
        private final SeatRepository seatRepository;
        private final EventShowRepository showRepository;
        private final ShowSeatRepository showSeatRepository;

        public ShowService(
                        EventRepository eventRepository,
                        VenueRepository venueRepository,
                        SeatRepository seatRepository,
                        EventShowRepository showRepository,
                        ShowSeatRepository showSeatRepository) {

                this.eventRepository = eventRepository;
                this.venueRepository = venueRepository;
                this.seatRepository = seatRepository;
                this.showRepository = showRepository;
                this.showSeatRepository = showSeatRepository;
        }

        @Transactional
        public ShowResponse createShow(
                        CreateShowRequest request) {

                if (!eventRepository.existsById(request.eventId())) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Event not found");
                }

                if (!venueRepository.existsById(request.venueId())) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Venue not found");
                }

                if (!request.startTime().isAfter(Instant.now())) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Show must be scheduled in the future");
                }

                List<Seat> seats = seatRepository
                                .findByVenue_IdOrderByRowLabelAscSeatNumberAsc(
                                                request.venueId());

                if (seats.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Venue has no seats");
                }

                EventShow show = showRepository.save(
                                new EventShow(
                                                request.eventId(),
                                                request.venueId(),
                                                request.startTime()));

                List<ShowSeat> inventory = seats.stream()
                                .map(seat -> new ShowSeat(
                                                show.getId(),
                                                request.venueId(),
                                                seat.getId(),
                                                request.ticketPrice()))
                                .toList();

                showSeatRepository.saveAll(inventory);

                // Ensures inserts execute before returning.
                showSeatRepository.flush();

                return new ShowResponse(
                                show.getId(),
                                show.getEventId(),
                                show.getVenueId(),
                                show.getStartTime(),
                                show.getStatus(),
                                inventory.size());
        }

        @Transactional(readOnly = true)
        public ShowResponse getShow(Long showId) {

                EventShow show = findShow(showId);

                return new ShowResponse(
                                show.getId(),
                                show.getEventId(),
                                show.getVenueId(),
                                show.getStartTime(),
                                show.getStatus(),
                                showSeatRepository.countByShowId(showId));
        }

        @Transactional(readOnly = true)
        public List<ShowSeatResponse> getShowSeats(Long showId) {

                // 1. Find the show
                EventShow show = findShow(showId);

                // 2. Fetch physical seats belonging to the venue
                List<Seat> physicalSeats = seatRepository
                                .findByVenue_IdOrderByRowLabelAscSeatNumberAsc(
                                                show.getVenueId());

                // 3. Create a lookup map for physical seats
                Map<Long, Seat> seatMap = physicalSeats.stream()
                                .collect(Collectors.toMap(
                                                Seat::getId,
                                                Function.identity()));

                // 4. Fetch show-specific seat inventory
                return showSeatRepository.findByShowId(showId)
                                .stream()

                                // Sort seats by row and seat number
                                .sorted(
                                                Comparator.comparing(
                                                                (ShowSeat ss) -> seatMap
                                                                                .get(ss.getSeatId())
                                                                                .getRowLabel())
                                                                .thenComparing(
                                                                                ss -> seatMap
                                                                                                .get(ss.getSeatId())
                                                                                                .getSeatNumber()))

                                // 5. Convert entities into response DTOs
                                .map(showSeat -> {

                                        Seat seat = seatMap.get(
                                                        showSeat.getSeatId());

                                        // 6. Check whether the hold has expired
                                        boolean isExpiredHold = "HELD".equals(showSeat.getStatus())
                                                        && showSeat.getHoldExpiresAt() != null
                                                        && !showSeat.getHoldExpiresAt()
                                                                        .isAfter(Instant.now());

                                        // 7. Determine effective availability
                                        String effectiveStatus = isExpiredHold
                                                        ? "AVAILABLE"
                                                        : showSeat.getStatus();

                                        // 8. Return the seat response
                                        return new ShowSeatResponse(
                                                        showSeat.getId(),
                                                        seat.getId(),
                                                        seat.getRowLabel(),
                                                        seat.getSeatNumber(),
                                                        seat.getSeatType(),
                                                        showSeat.getPrice(),
                                                        effectiveStatus);
                                })
                                .toList();
        }

        private EventShow findShow(Long id) {
                return showRepository.findById(id)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Show not found"));
        }

        @Transactional(readOnly = true)
        public List<ShowResponse> getShowsForEvent(Long eventId) {

                return showRepository
                                .findByEventIdAndStartTimeAfterAndStatusOrderByStartTimeAsc(
                                                eventId,
                                                Instant.now(),
                                                "SCHEDULED")
                                .stream()
                                .map(show -> new ShowResponse(
                                                show.getId(),
                                                show.getEventId(),
                                                show.getVenueId(),
                                                show.getStartTime(),
                                                show.getStatus(),
                                                showSeatRepository.countByShowId(show.getId())))
                                .toList();
        }

}
