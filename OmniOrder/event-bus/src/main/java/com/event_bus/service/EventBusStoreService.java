package com.event_bus.service;

import com.event_bus.dto.Event;
import com.event_bus.dto.EventRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class EventBusStoreService {

    private final ConcurrentMap<String, TopicLog> topics = new ConcurrentHashMap<>();

    /*
        The `append` method is responsible for adding a new event to a specified topic.
        It takes the topic name and the event data as parameters. The method first checks
        if the topic already exists in the `topics` map. If it does not exist, it creates
        a new `com.event_bus.service.TopicLog` instance for that topic using the `computeIfAbsent` method, which
        ensures that only one instance of `com.event_bus.service.TopicLog` is created for each topic even in a
        concurrent environment.

        computeIfAbsent` method takes a key (in this case, the topic name) and a mapping function
        that generates a value (a new `com.event_bus.service.TopicLog` instance) if the key is not already present
        in the map. This approach is thread-safe and efficient, as it avoids the need for
        explicit synchronization when checking for the existence of a topic and creating
        a new `com.event_bus.service.TopicLog`.

        traditionally, one might check if the topic exists and then create a new `com.event_bus.service.TopicLog`
        if it does not. However, this approach can lead to race conditions in a multi-threaded
        environment, where multiple threads might simultaneously check for the existence of
        the same topic and create multiple `com.event_bus.service.TopicLog` instances. By using `computeIfAbsent`,
        we ensure that only one thread can create the `com.event_bus.service.TopicLog` for a given topic at a time.
        After ensuring that the `com.event_bus.service.TopicLog` for the specified topic exists, the method calls
        the `appendEvent` method of the `com.event_bus.service.TopicLog` instance to add the new event and returns
        the offset of the newly added event.
     */
    public long append(String topic, Event event){
        TopicLog topicLog = topics.computeIfAbsent(topic, k -> new TopicLog());
        return topicLog.appendEvent(event);
    }


    /*
        The `read` method is responsible for retrieving a list of events from a specified topic,
        starting from a given offset and up to a specified limit. It takes three parameters: the
        topic name, the starting offset, and the maximum number of events to retrieve (limit).

        The method first checks if the specified topic exists in the `topics` map. If the topic
        does not exist, it returns an empty list, indicating that there are no events to read.
        If the topic exists, it retrieves the corresponding `com.event_bus.service.TopicLog` instance and calls its
        `readEvents` method, passing the starting offset and limit as arguments. The `readEvents`
        method of `com.event_bus.service.TopicLog` handles the logic of fetching the events based on the provided
        parameters and returns the list of events, which is then returned by the `read` method.
     */

    public List<EventRecord> read(String topic, long fromOffset, int limit){
        TopicLog topicLog = topics.get(topic);
        if(topicLog == null){
            return Collections.emptyList();
        }
        return topicLog.readEvents(fromOffset, limit);
    }

    public long size(String topic){
        TopicLog topicLog = topics.get(topic);
        if(topicLog == null){
            return 0;
        }
        return topicLog.size();
    }

}