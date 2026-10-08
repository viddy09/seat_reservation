package com.paytmmoney.seatreservation.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Runs N tasks on virtual threads, all released by one start gate so they hit the server at the same instant. Java
 * has no race detector; real contention, repeated, is the proof.
 */
public final class Concurrently {

    private Concurrently() {}

    public static <T> List<T> run(int count, IntFunction<T> task) {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch startGate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    startGate.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            startGate.countDown();
            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A concurrent task failed (transport error?)", e.getCause());
        }
    }
}
