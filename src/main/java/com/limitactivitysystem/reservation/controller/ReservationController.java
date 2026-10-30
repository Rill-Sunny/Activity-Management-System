package com.limitactivitysystem.reservation.controller;

import com.limitactivitysystem.reservation.dto.ReservationResponse;
import com.limitactivitysystem.reservation.exception.ReservationErrorCode;
import com.limitactivitysystem.reservation.exception.ReservationException;
import com.limitactivitysystem.reservation.service.FreeReservationService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class ReservationController {
    private final FreeReservationService reservations;
    public ReservationController(FreeReservationService reservations) { this.reservations = reservations; }

    @PostMapping("/sessions/{sessionId}/reservations")
    public ResponseEntity<ReservationResponse> create(@PathVariable("sessionId") @Positive Long sessionId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        requireEmptyBody(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(reservations.create(sessionId));
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") @Positive Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        requireEmptyBody(body);
        return reservations.cancel(id);
    }

    private void requireEmptyBody(Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            throw new ReservationException(ReservationErrorCode.INVALID_RESERVATION_REQUEST, "Reservation accepts no client-supplied fields");
        }
    }
}
