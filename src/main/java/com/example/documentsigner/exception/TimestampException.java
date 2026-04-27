package com.example.documentsigner.exception;

/**
 * Thrown when a TSA (Timestamp Authority) request fails.
 *
 * Callers may catch this and decide whether to fall back to PAdES-B
 * (using the local server clock) or to abort the signing operation.
 */
public class TimestampException extends Exception {

    public TimestampException(String message) {
        super(message);
    }

    public TimestampException(String message, Throwable cause) {
        super(message, cause);
    }
}
