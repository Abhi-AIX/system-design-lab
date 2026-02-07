package com.nettrace;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BadHealthController {

    /*
     * This endpoint simulates a bad health check by returning a 503 Service Unavailable status.
     * In a real application, you might have logic to determine the health status based on various factors such as database connectivity, external service availability, etc. For demonstration purposes, this endpoint simply returns a "DOWN" status with the current timestamp.
     * HTTP does mean good API design, but it does not guarantee it. It is up to the developer to design the API in a way that follows REST principles and best practices. In this case, the /badhealth endpoint is intentionally designed to simulate a bad health check for testing purposes, and it may not follow all REST principles. However, it serves its intended purpose of simulating a bad health check for demonstration purposes.
     * Rest architecture principles include statelessness, client-server separation, cacheability, layered system, uniform interface, and code on demand (optional). While the /badhealth endpoint may not fully adhere to all of these principles, it is still a valid HTTP endpoint that can be used for testing and demonstration purposes.
     * In summary, while the /badhealth endpoint may not fully comply with REST principles, it is a valid HTTP endpoint that serves its intended purpose of simulating a bad health check for testing and demonstration purposes.
     * We could do it in health controller, that would be more appropriate, but for demonstration purposes, we have created a separate controller to simulate a bad health check.
     * why this is bad does it represent a resource? no it does not represent a resource, it is just an endpoint that simulates a bad health check.
     * is it idempotent? no it is not idempotent, calling it multiple times will not have the same effect as calling it once.
     * if load balancer or do they care about status codes? yes they do care about status codes, they use them to determine the health of the service.
     * if there is action in the enspoint then responding with status code 503 would be inappropriate, but in this case there is no action, it is just a health check endpoint.
     * which one can evolve without breaking clients? example if we wanted to change the response body or add additional fields, we could do that without breaking clients as long as we maintain the same status code and endpoint.
     */
    @GetMapping("/badhealth")
    public ResponseEntity<HealthResponse> badHealthCheck() {
        HealthResponse response = new HealthResponse("DOWN", java.time.Instant.now());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    /*
    //breaking clients example:
    @GetMapping("/verybadhealth")
    //This endpoint returns a plain text response instead of a structured JSON response, which could break clients expecting a JSON response.
    public ResponseEntity<String> veryBadHealthCheck() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Service is down");
    }
    */
}
