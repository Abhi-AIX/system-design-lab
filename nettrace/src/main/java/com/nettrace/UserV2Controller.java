package com.nettrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("api/v2/users")
public class UserV2Controller {
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return Map.of(
                "id", id,
                "contactEmail", "a@b.com",
                "createdAt", Instant.now()
        );
    }
}
