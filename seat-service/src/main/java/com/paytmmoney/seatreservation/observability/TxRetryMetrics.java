package com.paytmmoney.seatreservation.observability;

import com.paytmmoney.seatreservation.persistence.TxRetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code db_tx_retries_total{operation,sqlstate}}: any non-zero rate means the lock order was violated somewhere. */
@Component
public class TxRetryMetrics implements TxRetry.RetryListener {

    static final String NAME = "db.tx.retries";

    private final MeterRegistry registry;

    public TxRetryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onRetry(String operation, String sqlState) {
        Counter.builder(NAME)
                .description("Transactions retried after a deadlock or serialization failure")
                .tag("operation", operation)
                .tag("sqlstate", sqlState)
                .register(registry)
                .increment();
    }
}
