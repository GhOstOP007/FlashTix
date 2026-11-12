
package com.flashtix.service;

import com.flashtix.dto.*;
import com.flashtix.entity.*;
import com.flashtix.repository.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class VenueService {

    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;

    public VenueService(
            VenueRepository venueRepository,
            SeatRepository seatRepository) {
        this.venueRepository = venueRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public VenueResponse createVenue(
            CreateVenueRequest request) {
        Venue venue = new Venue(
                request.name(),
                request.city(),
                request.address());

        venue = venueRepository.save(venue);

        List<Seat> seats = new ArrayList<>();

        for (int row = 0; row < request.rows(); row++) {
            String rowLabel = String.valueOf((char) ('A' + row));

            for (int number = 1; number <= request.seatsPerRow(); number++) {
                seats.add(new Seat(
                        venue,
                        rowLabel,
                        number,
                        "REGULAR"));
            }
        }

        seatRepository.saveAll(seats);

        return new VenueResponse(
                venue.getId(),
                venue.getName(),
                venue.getCity(),
                venue.getAddress(),
                seats.size());
    }

    @Transactional(readOnly = true)
    public List<VenueResponse> getAllVenues() {
        return venueRepository.findAll()
                .stream()
                .map(venue -> new VenueResponse(
                        venue.getId(),
                        venue.getName(),
                        venue.getCity(),
                        venue.getAddress(),
                        seatRepository.countByVenue_Id(
                                venue.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> getVenueSeats(Long venueId) {

        if (!venueRepository.existsById(venueId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Venue not found");
        }

        return seatRepository
                .findByVenue_IdOrderByRowLabelAscSeatNumberAsc(
                        venueId)
                .stream()
                .map(seat -> new SeatResponse(
                        seat.getId(),
                        seat.getRowLabel(),
                        seat.getSeatNumber(),
                        seat.getSeatType()))
                .toList();
    }
}
