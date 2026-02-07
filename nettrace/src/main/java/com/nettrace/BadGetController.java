package com.nettrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BadGetController {

    private int value = 0;

    //add an endpoint that increments a counter and returns the current value of the counter and the timestamp in a JSON response
    //but the endpoint should be a GET endpoint, and it should not be idempotent
    //this is a bad practice, but we want to test how the system handles it

    @GetMapping("/bad-get")
    public Map<String, Object> badGet() {
        value++;
        return Map.of("value", value, "timestamp", java.time.Instant.now());
    }

    //If client calls this endpoint multiple times, the value will keep incrementing, which is not expected for a GET endpoint. This can cause issues with caching and can lead to unexpected behavior in clients that rely on GET requests being idempotent.
    //will increment happens even if the client does not expect it, and it can cause issues with caching and can lead to unexpected behavior in clients that rely on GET requests being idempotent. This is a bad practice and should be avoided in real applications.
    //Did server protect against this bad practice? It should not, and it should allow the value to increment with each GET request. This is to test how the system handles this bad practice and to see if it can handle it without crashing or causing issues with caching.
    //will behavior be consistent across multiple calls? Yes, the value will increment with each GET request, and the timestamp will reflect the time of each request. This is to test how the system handles this bad practice and to see if it can handle it without crashing or causing issues with caching.
    //This is a bad practice and should be avoided in real applications, but we want to test how the system handles it and to see if it can handle it without crashing or causing issues with caching.


    /*
    🔒 Lock the rule (VERY IMPORTANT)
        Say this sentence:
        If a request changes server state, it must NOT be GET.
        Another one:
        GET may be repeated, cached, or retried without warning.
     */
}
