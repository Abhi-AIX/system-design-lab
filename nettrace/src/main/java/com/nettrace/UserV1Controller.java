package com.nettrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("api/v1/users")
public class UserV1Controller {

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return Map.of(
                "id", id,
                "email", "a@b.com"
        );
    }
}
