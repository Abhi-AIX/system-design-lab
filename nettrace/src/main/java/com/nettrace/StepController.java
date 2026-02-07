package com.nettrace;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class StepController {

    @GetMapping("/step")
    public Map<String, Object> step(
            @RequestParam(name = "value", required = false)
            String value
    ) {
        return Map.of(
                "receivedValue", value,
                "timestamp", Instant.now()
        );
    }
}
