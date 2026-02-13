package com.event_bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public class Event {

    private String eventId;
    private String eventType;
    private String key;
    private JsonNode payload;
    private Instant createdAt;

    public Event(){
        //jackson deserialization
    }

    public Event(String eventId, String eventType, String key, JsonNode payload, Instant createdAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.key = key;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getKey() {
        return key;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    // The createdAt field is set by the server and should not be deserialized from client input
    // We can ignore it during deserialization to ensure it's only set by the server
    @JsonIgnore
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
