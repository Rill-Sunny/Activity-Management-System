package com.limitactivitysystem.ticketorder.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class PaymentRetryBackoff {
    public void pause(int retryNumber) {
        long millis = 50L * retryNumber + ThreadLocalRandom.current().nextLong(26);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment retry backoff interrupted", exception);
        }
    }
}
