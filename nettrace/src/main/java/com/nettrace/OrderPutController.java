package com.nettrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class OrderPutController {

     private final Map<String, String> orders = new HashMap<>();

        @PutMapping("/orders/{id}")
        public Map<String, Object> placeOrder(
                @PathVariable("id") String id
        ) {
            orders.put(id, "CREATED");
            return Map.of("orderId", id, "status", orders.get(id), "timestamp", java.time.Instant.now());
        }
}
