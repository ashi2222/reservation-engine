package com.ashish.reservation_engine.controller;

import com.ashish.reservation_engine.dto.CreateReservationRequest;
import com.ashish.reservation_engine.dto.ReservationResponse;
import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> createReservation(@Valid @RequestBody CreateReservationRequest request) {
        ReservationService.ReservationResult result = reservationService.createReservationWithResult(
                request.getResourceId(),
                request.getUserId(),
                request.getQuantity(),
                request.getIdempotencyKey()
        );

        ReservationResponse response = new ReservationResponse(result.reservation());
        if (result.newlyCreated()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/{id}")
    public ReservationResponse getReservationById(@PathVariable Long id) {
        Reservation reservation = reservationService.getReservationById(id);
        return new ReservationResponse(reservation);
    }

    @DeleteMapping("/{id}")
    public ReservationResponse cancelReservation(@PathVariable Long id) {
        Reservation reservation = reservationService.cancelReservation(id);
        return new ReservationResponse(reservation);
    }

    @PostMapping("/{id}/payment-pending")
    public ReservationResponse transitionToPaymentPending(@PathVariable Long id) {
        Reservation reservation = reservationService.transitionToPaymentPending(id);
        return new ReservationResponse(reservation);
    }

    @PostMapping("/{id}/confirm")
    public ReservationResponse confirmReservation(@PathVariable Long id) {
        Reservation reservation = reservationService.confirmReservation(id);
        return new ReservationResponse(reservation);
    }
}
