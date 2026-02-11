package com.nettrace;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    //add health check endpoint that returns a JSON response with the status and timestamp, and a 200 OK status code

    public volatile boolean isHealthy = true;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> healthCheck() {

        //Request arrived at health check endpoint, checking health status
        HealthResponse response = new HealthResponse(isHealthy ? "UP" : "DOWN", java.time.Instant.now());

        //response constructed
        return ResponseEntity
                .status(isHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
        // 3. Spring/Tomcat serialize and send

    }

    @PostMapping("/health/down")
    public void setHealthDown() {
        isHealthy = false;
    }
    @PostMapping("/health/up")
    public void setHealthUp() {
        isHealthy = true;
    }

}
