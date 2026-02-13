package com.event_bus.controller;

import com.event_bus.dto.Event;
import com.event_bus.dto.EventRecord;
import com.event_bus.service.EventBusStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/topics")
public class EventBusStoreController {

    private final EventBusStoreService eventBusStoreService;

    public EventBusStoreController(EventBusStoreService eventBusStoreService) {
        this.eventBusStoreService = eventBusStoreService;
     }

     @PostMapping("/{topic}/events")
        public ResponseEntity<Map<String, Object>> publish(
                @PathVariable String topic,
                @RequestBody Event event) {

            validateEvent(event);

            // Set server-side timestamp - this ensures the timestamp is controlled by the server
            event.setCreatedAt(Instant.now());

            long offset = eventBusStoreService.append(topic, event);
            URI location = URI.create("/topics/" + topic + "/events/" + offset);

            Map<String, Object> response = Map.of(
                    "offset", offset
            );

            return ResponseEntity
                    .created(location)
                    .body(response);
     }


     @GetMapping("/{topic}/events")
        public ResponseEntity<Map<String, Object>> read(
                @PathVariable String topic,
                @RequestParam long fromOffset,
                @RequestParam int limit) {

         if (fromOffset < 0 || limit <= 0) {
             return ResponseEntity.badRequest().build();
         }

         List<EventRecord> events = eventBusStoreService.read(topic, fromOffset, limit);
         long nextOffset = fromOffset + events.size();

         Map<String, Object> response = Map.of(
                 "events", events,
                 "nextOffset", nextOffset
         );

         return ResponseEntity.ok(response);

     }

     private void validateEvent(Event event) {
         if (event.getEventId() == null ||
                    event.getEventType() == null ||
                    event.getKey() == null ||
                    event.getPayload() == null) {

             throw new ResponseStatusException(
                     HttpStatus.BAD_REQUEST,
                     "Missing required fields");
         }
     }

}
