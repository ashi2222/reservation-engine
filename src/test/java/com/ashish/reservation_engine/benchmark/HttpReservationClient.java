package com.ashish.reservation_engine.benchmark;

import com.ashish.reservation_engine.dto.CreateReservationRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Executes reservation requests via HTTP against a live or running Reservation Engine instance.
 * Uses Java 11+ standard HttpClient for high performance concurrent requests.
 */
public class HttpReservationClient implements ReservationClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpReservationClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), Duration.ofSeconds(10));
    }

    public HttpReservationClient(String baseUrl, HttpClient httpClient, Duration requestTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public RequestResult execute(int requestIndex, CreateReservationRequest request) {
        String url = baseUrl + "/api/reservations";
        String jsonPayload = String.format(
                "{\"resourceId\":%d,\"userId\":\"%s\",\"quantity\":%d,\"idempotencyKey\":\"%s\"}",
                request.getResourceId(),
                escapeJson(request.getUserId()),
                request.getQuantity(),
                escapeJson(request.getIdempotencyKey())
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        long startNano = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latencyNano = System.nanoTime() - startNano;
            int statusCode = response.statusCode();

            if (statusCode == 200 || statusCode == 201) {
                return RequestResult.success(
                        requestIndex,
                        request.getUserId(),
                        request.getIdempotencyKey(),
                        statusCode,
                        latencyNano,
                        response.body()
                );
            } else if (statusCode == 409) {
                return RequestResult.rejected(
                        requestIndex,
                        request.getUserId(),
                        request.getIdempotencyKey(),
                        statusCode,
                        latencyNano,
                        response.body()
                );
            } else {
                return RequestResult.failed(
                        requestIndex,
                        request.getUserId(),
                        request.getIdempotencyKey(),
                        statusCode,
                        latencyNano,
                        "Unexpected status: " + statusCode + " - " + response.body(),
                        null
                );
            }
        } catch (Exception ex) {
            long latencyNano = System.nanoTime() - startNano;
            return RequestResult.failed(
                    requestIndex,
                    request.getUserId(),
                    request.getIdempotencyKey(),
                    500,
                    latencyNano,
                    "HTTP dispatch failed: " + ex.getMessage(),
                    ex
            );
        }
    }

    private String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}

