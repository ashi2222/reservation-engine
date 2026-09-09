package com.ashish.reservation_engine.benchmark;

import com.ashish.reservation_engine.controller.ReservationController;
import com.ashish.reservation_engine.dto.CreateReservationRequest;
import com.ashish.reservation_engine.dto.ReservationResponse;
import org.springframework.http.ResponseEntity;

/**
 * Executes reservation requests directly against the Spring ReservationController in-process.
 * Translates controller responses and domain exceptions into structured RequestResults.
 */
public class ControllerReservationClient implements ReservationClient {

    private final ReservationController reservationController;

    public ControllerReservationClient(ReservationController reservationController) {
        this.reservationController = reservationController;
    }

    @Override
    public RequestResult execute(int requestIndex, CreateReservationRequest request) {
        long startNano = System.nanoTime();
        try {
            ResponseEntity<ReservationResponse> response = reservationController.createReservation(request);
            long latencyNano = System.nanoTime() - startNano;
            int statusCode = response.getStatusCode().value();

            String message = response.getBody() != null
                    ? "Reservation ID: " + response.getBody().getId()
                    : "Status: " + statusCode;

            return RequestResult.success(
                    requestIndex,
                    request.getUserId(),
                    request.getIdempotencyKey(),
                    statusCode,
                    latencyNano,
                    message
            );
        } catch (IllegalStateException ex) {
            long latencyNano = System.nanoTime() - startNano;
            // Insufficient capacity is a business rejection (HTTP 409 Conflict)
            if (ex.getMessage() != null && ex.getMessage().contains("Insufficient capacity")) {
                return RequestResult.rejected(
                        requestIndex,
                        request.getUserId(),
                        request.getIdempotencyKey(),
                        409,
                        latencyNano,
                        ex.getMessage()
                );
            }
            return RequestResult.failed(
                    requestIndex,
                    request.getUserId(),
                    request.getIdempotencyKey(),
                    500,
                    latencyNano,
                    ex.getMessage(),
                    ex
            );
        } catch (Exception ex) {
            long latencyNano = System.nanoTime() - startNano;
            return RequestResult.failed(
                    requestIndex,
                    request.getUserId(),
                    request.getIdempotencyKey(),
                    500,
                    latencyNano,
                    ex.getMessage(),
                    ex
            );
        }
    }
}

