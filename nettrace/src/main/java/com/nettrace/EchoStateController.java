package com.nettrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class EchoStateController {

    @GetMapping("/echo-state")
    public Map<String, Object> echoState(
            @RequestHeader(value = "X-Client-State", required = false) String clientState
    ) {
        return Map.of("clientState", clientState, "timestamp", java.time.Instant.now());
    }
}
