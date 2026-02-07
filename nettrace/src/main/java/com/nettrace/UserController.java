package com.nettrace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/users")
public class UserController {

    private final Set<String> emails = new HashSet<>();

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest req) {
        // 400 — malformed / missing
        if (req == null || req.email() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "email is required"
            ));
        }

        // 422 — understood but violates business rules
        if (!req.email().contains("@")) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "VALIDATION_FAILED",
                    "field", "email",
                    "message", "email format is invalid"
            ));
        }

        if (emails.contains(req.email())) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "DUPLICATE",
                    "field", "email",
                    "message", "email already exists"
            ));
        }

        emails.add(req.email());
        return ResponseEntity.status(201).body(Map.of(
                "status", "CREATED",
                "email", req.email()
        ));

    }
}
