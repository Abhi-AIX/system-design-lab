package com.nettrace;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RestController
public class PaymentController {

    private final Set<String> processKeys = new HashSet<>();

    @PostMapping("/payments")
    public Map<String, Object> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        if (processKeys.contains(idempotencyKey)) {
            return Map.of("error", "Duplicate request", "timestamp", java.time.Instant.now());
        }

        processKeys.add(idempotencyKey);
        // Simulate payment processing logic here
        return Map.of("status", "Payment processed", "timestamp", java.time.Instant.now());

    }
}
