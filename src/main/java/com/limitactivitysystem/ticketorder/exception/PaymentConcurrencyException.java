package com.limitactivitysystem.ticketorder.exception;

/** A technical concurrency failure after all complete payment attempts have rolled back. */
public class PaymentConcurrencyException extends RuntimeException {
    public static final String CODE = "PAYMENT_CONCURRENCY_RETRY_EXHAUSTED";
    public enum Reason { DEADLOCK, LOCK_WAIT_TIMEOUT }

    private final Reason reason;
    private final int attempts;

    public PaymentConcurrencyException(Reason reason, int attempts, Throwable cause) {
        super("Payment could not complete because of concurrent database access; try again later", cause);
        this.reason = reason;
        this.attempts = attempts;
    }

    public Reason getReason() { return reason; }
    public int getAttempts() { return attempts; }
}
