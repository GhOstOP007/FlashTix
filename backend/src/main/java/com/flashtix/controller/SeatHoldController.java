
package com.flashtix.controller;

import com.flashtix.dto.SeatHoldResponse;
import com.flashtix.service.SeatHoldService;

import com.flashtix.dto.ReleaseSeatHoldRequest;
import com.flashtix.dto.SeatReleaseResponse;
import jakarta.validation.Valid;

import com.flashtix.dto.MultiSeatHoldRequest;
import com.flashtix.dto.MultiSeatHoldResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shows")
public class SeatHoldController {

    private final SeatHoldService seatHoldService;

    public SeatHoldController(
            SeatHoldService seatHoldService) {
        this.seatHoldService = seatHoldService;
    }

    @PostMapping("/{showId}/seats/{seatId}/hold")
    public SeatHoldResponse holdSeat(
            @PathVariable Long showId,
            @PathVariable Long seatId) {

        return seatHoldService.holdSeat(showId, seatId);
    }

    @PostMapping("/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public MultiSeatHoldResponse holdMultipleSeats(
            @PathVariable Long showId,
            @Valid @RequestBody MultiSeatHoldRequest request) {

        return seatHoldService.holdMultipleSeats(
                showId,
                request);
    }

    @DeleteMapping("/{showId}/seats/{seatId}/hold")
    public SeatReleaseResponse releaseHold(
            @PathVariable Long showId,
            @PathVariable Long seatId,
            @Valid @RequestBody ReleaseSeatHoldRequest request) {

        return seatHoldService.releaseSeat(
                showId,
                seatId,
                request.holdToken());
    }

}
