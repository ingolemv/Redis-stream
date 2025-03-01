package com.lockservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockservice.model.RequestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class RequestStatusProducer {

    @Value("${redis.stream.status-key:request-status-stream}")
    private String streamKey;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RequestStatusRepository statusRepository;

    @Autowired
    public RequestStatusProducer(
            RedisTemplate<String, String> redisTemplate, 
            ObjectMapper objectMapper,
            RequestStatusRepository statusRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.statusRepository = statusRepository;
    }

    /**
     * Publish a status update to the Redis stream
     * @param requestStatus The status to publish
     * @return The record ID of the published message
     */
    public RecordId publishStatusUpdate(RequestStatus requestStatus) {
        try {
            // Ensure timestamps are set
            if (requestStatus.getCreatedAt() == null) {
                requestStatus.setCreatedAt(LocalDateTime.now());
            }
            requestStatus.setUpdatedAt(LocalDateTime.now());

            // Convert to map of string values for Redis stream
            Map<String, String> statusMap = new HashMap<>();
            statusMap.put("requestId", requestStatus.getRequestId());
            statusMap.put("status", requestStatus.getStatus());
            statusMap.put("userId", requestStatus.getUserId());
            statusMap.put("createdAt", requestStatus.getCreatedAt().toString());
            statusMap.put("updatedAt", requestStatus.getUpdatedAt().toString());
            statusMap.put("source", requestStatus.getSource());
            
            if (requestStatus.getProgress() != null) {
                statusMap.put("progress", requestStatus.getProgress().toString());
            }
            if (requestStatus.getTotalSteps() != null) {
                statusMap.put("totalSteps", requestStatus.getTotalSteps().toString());
            }
            if (requestStatus.getMetadata() != null) {
                statusMap.put("metadata", objectMapper.writeValueAsString(requestStatus.getMetadata()));
            }

            // Create a record and publish to the stream
            StringRecord record = StreamRecords.string(statusMap).withStreamKey(streamKey);
            RecordId recordId = redisTemplate.opsForStream().add(record);
            
            // Save immediately to repository for faster access
            statusRepository.saveStatus(requestStatus);
            
            log.info("Published status update for request {} with status {} to stream: {}", 
                    requestStatus.getRequestId(), requestStatus.getStatus(), recordId);
            
            return recordId;
        } catch (Exception e) {
            log.error("Error publishing status update to Redis stream", e);
            throw new RuntimeException("Failed to publish status update", e);
        }
    }

    /**
     * Create and publish a new status update
     */
    public RequestStatus publishStatus(String requestId, String status, String userId, 
                                  Map<String, Object> metadata, String source,
                                  Integer progress, Integer totalSteps) {
        RequestStatus requestStatus = RequestStatus.builder()
                .requestId(requestId)
                .status(status)
                .userId(userId)
                .metadata(metadata)
                .source(source)
                .progress(progress)
                .totalSteps(totalSteps)
                .createdAt(LocalDateTime.now())
                .build();
        
        publishStatusUpdate(requestStatus);
        return requestStatus;
    }
} 