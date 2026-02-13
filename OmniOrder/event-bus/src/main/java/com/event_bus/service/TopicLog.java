package com.event_bus.service;

import com.event_bus.dto.Event;
import com.event_bus.dto.EventRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/*
    The `com.event_bus.service.TopicLog` class is designed to manage a log of events in a thread-safe manner.
    It provides functionality to append new events to the log and read events from the log
    based on a specified offset and limit.
    The class uses a `List<com.event_bus.dto.EventRecord>` to store the events, where each `com.event_bus.dto.EventRecord` contains
    an offset and the event data. The offset is used to keep track of the position of each event
    in the log. To manage concurrent access to the event log, the class uses a `ReentrantLock`.
    This lock ensures that only one thread can modify the event log at a time, preventing data corruption
    and ensuring thread safety. When a thread wants to append an event or read events, it must
    acquire the lock before performing the operation and release it afterward.
 */
public class TopicLog{

    private final List<EventRecord> eventRecord = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    /*
        The `appendEvent` method is responsible for adding a new event to the log.
        It first acquires the lock to ensure that no other thread can modify the event log while it is being updated.
        It then calculates the offset for the new event, which is simply the current size of the event log (i.e., the number of events already in the log).
        A new `com.event_bus.dto.EventRecord` is created with this offset and the event data, and it is added to the list of events.
        Finally, the lock is released, allowing other threads to access the event log.

     */
    public long appendEvent(Event event){
        lock.lock();
        try {
            long offSet = eventRecord.size();
            eventRecord.add(new EventRecord(offSet, event));
            return offSet;
        } finally {
            lock.unlock();
        }
    }


    /*
        The `readEvents` method allows clients to read a specified number of events from the log, starting from a given offset.
        It first acquires the lock to ensure thread safety while accessing the event log.
        The method checks if the provided offset is valid (i.e., non-negative and within the bounds of the event log).
        If the offset is invalid or if the limit is non-positive, it returns an empty list. Otherwise, it calculates the
        index of the last event to read based on the provided offset and limit, ensuring that it does not exceed the size
        of the event log. It then creates a sublist of events from the specified offset to the calculated end index and
        returns it as a new list. Finally, the lock is released.

    */
    public List<EventRecord> readEvents(long fromOffSet, int limit){

        lock.lock();

        try {
            if(fromOffSet<0){
                throw new IllegalArgumentException("Invalid offset: " + fromOffSet);
            }

            if(fromOffSet>=eventRecord.size() || limit <= 0){
                 return Collections.emptyList();
            }



            /*
               example: if fromOffSet is 5 and limit is 10, and there are only 12 events in the log, then fromOffSet + limit would be 15, which exceeds the size of the log.
               In this case, toIndex would be set to 12 (the size of the log), and the method would return events from offset 5 to offset 11 (inclusive), which are the available events in that range.
               The reason we calculate `toIndex` as `fromOffSet + limit` instead of just using `limit` is because `limit` represents the number of events to read, not the index of the last event to read.
               The `fromOffSet` is the starting point in the event log from which we want to read events. To determine the index of the last event to read, we need to add
               the `limit` (the number of events to read) to the `fromOffSet` (the starting index). This way, we can calculate the correct range of events to return based on the specified offset and limit.
               If we were to use just `limit` without adding it to `fromOffSet`, we would not be able to determine the correct range of events to return, as `limit`
               alone does not indicate where in the event log to start reading from.

             */
            int toIndex = (int) Math.min(fromOffSet + limit, eventRecord.size());
            return new ArrayList<>(eventRecord.subList((int) fromOffSet, toIndex));
            } finally {
            lock.unlock();
            }
    }

    /*
        The `size` method returns the total number of events currently stored in the log.
        It acquires the lock to ensure thread safety while accessing the event log and returns
        the size of the events list. Finally, it releases the lock.

        I understand lock for append and read but why do we need lock for size method?
        is it possible that while we are calculating the size, another thread is appending
        an event to the log, which could lead to an inconsistent or incorrect size being returned.
        By acquiring the lock before accessing the events list, we ensure that the
        size is calculated based on a consistent state of the event log, preventing
        potential race conditions and ensuring that the returned size is accurate
        even in a concurrent environment.

     */

    public long size() {
        lock.lock();
        try {
            return eventRecord.size();
        } finally {
            lock.unlock();
        }
    }

    }
