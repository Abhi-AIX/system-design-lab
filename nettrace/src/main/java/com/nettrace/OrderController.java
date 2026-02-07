package com.nettrace;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OrderController {

    private int orderCount = 0;

    @PostMapping("/orders")
    public Map<String, Object> placeOrder() {
        orderCount++;
        return Map.of("orderId", orderCount, "timestamp", java.time.Instant.now());

    }
}
