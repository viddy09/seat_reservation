package com.paytmmoney.seatreservation.common;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;

/**
 * Classifies database failures by SQLSTATE on the root-most {@link SQLException}. SQLSTATE is Postgres' stable
 * contract; Spring's exception subclasses have shifted between versions, so retry and HTTP mapping never depend on
 * them (ADR-013, ADR-014).
 */
public final class SqlStates {

    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String QUERY_CANCELED = "57014";
    public static final String ADMIN_SHUTDOWN = "57P01";
    public static final String CANNOT_CONNECT_NOW = "57P03";
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    public enum Failure {
        /** Deadlock or serialization failure: retry the whole transaction. */
        RETRYABLE,
        /** Pool-acquire timeout or statement timeout: the database is healthy but saturated (429). */
        BUSY,
        /** Cannot reach the database (503, fail closed). */
        UNAVAILABLE,
        /** Anything else is a bug. */
        OTHER
    }

    private SqlStates() {}

    public static Optional<String> sqlState(Throwable error) {
        String state = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                state = sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.ofNullable(state);
    }

    public static Failure classify(Throwable error) {
        Optional<String> state = sqlState(error);
        if (state.isPresent()) {
            String code = state.get();
            if (DEADLOCK_DETECTED.equals(code) || SERIALIZATION_FAILURE.equals(code)) {
                return Failure.RETRYABLE;
            }
            if (QUERY_CANCELED.equals(code)) {
                return Failure.BUSY;
            }
            if (code.startsWith(CONNECTION_EXCEPTION_CLASS)
                    || ADMIN_SHUTDOWN.equals(code)
                    || CANNOT_CONNECT_NOW.equals(code)) {
                return Failure.UNAVAILABLE;
            }
            return Failure.OTHER;
        }
        // Hikari's acquire timeout has no SQLSTATE when the pool is merely exhausted (when the database is down it
        // carries the last connection error's 08xxx state and is classified above).
        return hasCause(error, SQLTransientConnectionException.class) ? Failure.BUSY : Failure.OTHER;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
