package com.nettrace;

import java.time.Instant;

public record HealthResponse(
    String status,
    Instant timestamp
) {
}
