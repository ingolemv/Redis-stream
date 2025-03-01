package com.lockservice.repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import com.lockservice.model.Lock;
import com.lockservice.model.LockStatus;

@Repository
public class LockRepository {

    private final RedisTemplate<String, Lock> redisTemplate;
    private final String lockKeyPrefix = "lock:";
    
    @Value("${redis.stream.key}")
    private String streamKey;

    @Autowired
    public LockRepository(RedisTemplate<String, Lock> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean saveLock(Lock lock) {
        String key = lockKeyPrefix + lock.getRequestId();
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, lock));
    }

    public boolean updateLock(Lock lock) {
        String key = lockKeyPrefix + lock.getRequestId();
        redisTemplate.opsForValue().set(key, lock);
        
        // Add event to Redis Stream
        Map<String, String> eventData = Map.of(
            "requestId", lock.getRequestId(),
            "userId", lock.getUserId(),
            "status", lock.getStatus().toString(),
            "timestamp", LocalDateTime.now().toString()
        );
        
        redisTemplate.opsForStream().add(streamKey, eventData);
        return true;
    }

    public Optional<Lock> findLockByRequestId(String requestId) {
        String key = lockKeyPrefix + requestId;
        Lock lock = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(lock);
    }

    public boolean releaseLock(String requestId, String userId) {
        String key = lockKeyPrefix + requestId;
        Lock lock = redisTemplate.opsForValue().get(key);
        
        if (lock != null && lock.getUserId().equals(userId)) {
            lock.setStatus(LockStatus.RELEASED);
            lock.setUpdatedAt(LocalDateTime.now());
            redisTemplate.opsForValue().set(key, lock);
            
            // Add release event to Redis Stream
            Map<String, String> eventData = Map.of(
                "requestId", lock.getRequestId(),
                "userId", lock.getUserId(),
                "status", LockStatus.RELEASED.toString(),
                "timestamp", LocalDateTime.now().toString()
            );
            
            redisTemplate.opsForStream().add(streamKey, eventData);
            return true;
        }
        
        return false;
    }

    public boolean deleteLock(String requestId) {
        String key = lockKeyPrefix + requestId;
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }
} 