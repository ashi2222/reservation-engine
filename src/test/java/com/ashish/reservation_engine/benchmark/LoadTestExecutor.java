package com.ashish.reservation_engine.benchmark;

import com.ashish.reservation_engine.dto.CreateReservationRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes concurrent reservation requests against a ReservationClient and collects benchmark metrics.
 * Uses a fixed thread pool and a synchronized latch to unleash all concurrent worker threads simultaneously.
 */
public class LoadTestExecutor {

    private final ReservationClient client;

    public LoadTestExecutor(ReservationClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * Executes the benchmark described by the given BenchmarkConfig.
     *
     * @param config benchmark configuration containing concurrency, request count, and payloads
     * @return aggregated BenchmarkResult with latencies and throughput metrics
     * @throws InterruptedException if interrupted while waiting for workers to complete
     */
    public BenchmarkResult execute(BenchmarkConfig config) throws InterruptedException {
        int totalRequests = config.getTotalRequests();
        int concurrencyLevel = config.getConcurrencyLevel();

        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);
        CountDownLatch readyLatch = new CountDownLatch(concurrencyLevel);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalRequests);

        ConcurrentLinkedQueue<RequestResult> resultsQueue = new ConcurrentLinkedQueue<>();
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger rejectedCounter = new AtomicInteger(0);
        AtomicInteger failedCounter = new AtomicInteger(0);

        // Submit all requests to thread pool
        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    // Signal readiness for initial pool threads
                    if (index < concurrencyLevel) {
                        readyLatch.countDown();
                    }
                    // Wait for start gate to release all threads simultaneously
                    startGate.await();

                    String userId = config.getUserIdGenerator().apply(index);
                    String idempotencyKey = config.getIdempotencyKeyGenerator().apply(index);

                    CreateReservationRequest request = new CreateReservationRequest(
                            config.getResourceId(),
                            userId,
                            config.getQuantity(),
                            idempotencyKey
                    );

                    RequestResult result = client.execute(index, request);
                    resultsQueue.add(result);

                    if (result.isSuccess()) {
                        successCounter.incrementAndGet();
                    } else if (result.isRejected()) {
                        rejectedCounter.incrementAndGet();
                    } else {
                        failedCounter.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    RequestResult failure = RequestResult.failed(
                            index,
                            "interrupted",
                            "interrupted",
                            500,
                            0,
                            "Worker thread interrupted",
                            ie
                    );
                    resultsQueue.add(failure);
                    failedCounter.incrementAndGet();
                } catch (Exception ex) {
                    RequestResult failure = RequestResult.failed(
                            index,
                            "unknown",
                            "unknown",
                            500,
                            0,
                            "Unexpected execution failure: " + ex.getMessage(),
                            ex
                    );
                    resultsQueue.add(failure);
                    failedCounter.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Wait for all worker threads to be spun up and ready
        boolean ready = readyLatch.await(10, TimeUnit.SECONDS);
        if (!ready) {
            // Proceed even if ready latch timed out to avoid deadlock on low resources
        }

        // Release the start gate and measure total execution duration
        long startNano = System.nanoTime();
        startGate.countDown();

        // Await completion of all requests
        doneGate.await();
        long totalDurationNano = System.nanoTime() - startNano;

        // Cleanly terminate executor
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        List<RequestResult> sortedResults = new ArrayList<>(resultsQueue);
        sortedResults.sort(Comparator.comparingInt(RequestResult::getRequestIndex));

        return new BenchmarkResult(
                config,
                totalRequests,
                successCounter.get(),
                rejectedCounter.get(),
                failedCounter.get(),
                totalDurationNano,
                sortedResults
        );
    }
}

