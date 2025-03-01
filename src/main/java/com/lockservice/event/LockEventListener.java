package com.lockservice.event;


import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import com.lockservice.repository.LockRepository;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class LockEventListener implements StreamListener<String, MapRecord<String, Object, Object>> {

    @Value("${redis.stream.key}")
    private String streamKey;
    
    @Value("${redis.stream.consumer-group}")
    private String consumerGroup;
    
    @Value("${redis.stream.consumer-name}")
    private String consumerName;

    private final RedisTemplate<String, String> redisTemplate;
    private final LockRepository lockRepository;
    private final LockEventPublisher eventPublisher;

    @Autowired
    public LockEventListener(
            RedisTemplate<String, String> redisTemplate,
            LockRepository lockRepository,
            LockEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.lockRepository = lockRepository;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void init() {
        try {
            // Create consumer group if not exists
            redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
        } catch (Exception e) {
            log.info("Consumer group already exists or couldn't be created: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1000)
    @SuppressWarnings({ "unchecked", "null" })
    public void consumeEvents() {
        try {
            redisTemplate.opsForStream()
                .read(Consumer.from(consumerGroup, consumerName),
                      StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                .forEach(this::onMessage);
        } catch (Exception e) {
            log.error("Error consuming stream events", e);
        }
    }

    @Override
    public void onMessage(MapRecord<String, Object, Object> message) {
        try {
            Map<Object, Object> body = message.getValue();
            String requestId = body.get("requestId").toString();
            
            lockRepository.findLockByRequestId(requestId).ifPresent(eventPublisher::publishLockUpdate);
            System.out.println("message ----- "+body);
            // Acknowledge the message
            redisTemplate.opsForStream().acknowledge(consumerGroup, message);
            
        } catch (Exception e) {
            log.error("Error processing stream event", e);
        }
    }
} 