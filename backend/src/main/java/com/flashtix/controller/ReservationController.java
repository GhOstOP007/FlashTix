
package com.flashtix.controller;

import com.flashtix.dto.AuthenticatedReservationRequest;
import com.flashtix.dto.ReservationResponse;
import com.flashtix.service.AuthenticatedReservationService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final AuthenticatedReservationService service;

    public ReservationController(
            AuthenticatedReservationService service) {

        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse createReservation(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AuthenticatedReservationRequest request) {

        UUID userId = UUID.fromString(
                jwt.getSubject());

        return service.createReservation(userId, request);
    }

    @GetMapping("/{reservationId}")
    public ReservationResponse getReservation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reservationId) {

        UUID userId = UUID.fromString(
                jwt.getSubject());

        return service.getReservation(
                userId,
                reservationId);
    }

    @GetMapping
    public List<ReservationResponse> getHistory(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(
                jwt.getSubject());

        return service.getHistory(userId);
    }

    @PostMapping("/{reservationId}/cancel")
    public ReservationResponse cancelReservation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reservationId) {

        UUID userId = UUID.fromString(
                jwt.getSubject());

        return service.cancelReservation(
                userId,
                reservationId);
    }
}
