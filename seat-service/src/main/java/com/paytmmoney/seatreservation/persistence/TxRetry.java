package com.paytmmoney.seatreservation.persistence;

import com.paytmmoney.seatreservation.common.SqlStates;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Re-runs a whole transaction on deadlock (40P01) or serialization failure (40001). It must wrap the transaction from
 * the outside: an aborted Postgres transaction cannot continue, so each attempt has to be a fresh one (ADR-013).
 *
 * <p>With the global lock order (ADR-006) this should never fire; it is a safety net, and every retry is counted so a
 * non-zero rate pages someone.
 */
@Component
public class TxRetry {

    static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 10;
    private static final long MAX_JITTER_MILLIS = 40;

    private static final Logger log = LoggerFactory.getLogger(TxRetry.class);

    private final RetryListener listener;

    public TxRetry(RetryListener listener) {
        this.listener = listener;
    }

    /**
     * @param operation name used in logs and the retry metric
     * @param transaction one complete transaction; domain exceptions thrown from it pass straight through
     * @param onExhausted the exception to throw when every attempt hit a retryable failure
     */
    public <T> T run(String operation, Supplier<T> transaction, Supplier<? extends RuntimeException> onExhausted) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (DataAccessException e) {
                if (SqlStates.classify(e) != SqlStates.Failure.RETRYABLE) {
                    throw e;
                }
                String sqlState = SqlStates.sqlState(e).orElse("unknown");
                listener.onRetry(operation, sqlState);
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("Transaction retries exhausted: operation={} sqlstate={}", operation, sqlState, e);
                    throw onExhausted.get();
                }
                log.warn("Retrying transaction: operation={} sqlstate={} attempt={}", operation, sqlState, attempt);
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) {
        long millis = (BASE_BACKOFF_MILLIS << (attempt - 1))
                + ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS);
        try {
            Thread.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off a transaction retry", e);
        }
    }

    /** Hook for the {@code db_tx_retries_total} metric (wired in the observability layer). */
    @FunctionalInterface
    public interface RetryListener {
        void onRetry(String operation, String sqlState);
    }
}
