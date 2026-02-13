package com.event_bus.dto;

public class EventRecord {

    private final long offSet;
    private final Event event;

    public EventRecord(long offSet, Event event) {
        this.offSet = offSet;
        this.event = event;
    }

    public long getOffSet() {
        return offSet;
    }

    public Event getEvent() {
        return event;
    }
}