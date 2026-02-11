package com.nettrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class EchoStateController {

    @GetMapping("/echo-state")
    public Map<String, Object> echoState(
            //explain this X-Client-State header and how it can be used to pass client state information to the server, and how the server can use this information to provide a more personalized response
            // The X-Client-State header is a custom HTTP header that clients can use to send state information to the server. This can include things like user preferences, session data, or any other context that the client wants the server to be aware of. By including this header in the request, the client can provide additional information that the server can use to tailor its response.
            // For example, if a client includes a user preference in the X-Client-State header, the server can read this information and use it to customize the response. This could involve returning content in a preferred language, adjusting the format of the response based on user settings, or providing personalized recommendations. The server can also use this state information to maintain continuity across multiple requests, allowing for a more seamless and personalized user experience.
            @RequestHeader(value = "X-Client-State", required = false) String clientState
    ) {
        return Map.of("clientState", clientState, "timestamp", java.time.Instant.now());
    }
}
