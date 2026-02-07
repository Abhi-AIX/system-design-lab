package com.nettrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CounterController {

    private int counter = 0;

    //add an endpoint that increments a counter and returns the current value of the counter and the timestamp in a JSON response
    //stat
    @GetMapping("/counter")
    public Map<String, Object> getCounter() {
        counter++;

        return Map.of("counter", counter, "timestamp",
                java.time.Instant.now());
    }

}
