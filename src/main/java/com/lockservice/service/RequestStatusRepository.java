package com.lockservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockservice.model.RequestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class RequestStatusRepository {

    private static final String STATUS_KEY_PREFIX = "request:status:";
    private static final String STATUS_HISTORY_KEY_PREFIX = "request:status:history:";
    private static final String STATUS_INDEX_KEY = "request:status:index";
    private static final String STATUS_BY_TYPE_PREFIX = "request:status:type:";
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public RequestStatusRepository(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Save the current status of a request
     */
    public void saveStatus(RequestStatus status) {
        try {
            String requestId = status.getRequestId();
            String statusJson = objectMapper.writeValueAsString(status);
            
            // Save current status
            String currentStatusKey = STATUS_KEY_PREFIX + requestId;
            redisTemplate.opsForValue().set(currentStatusKey, statusJson);
            
            // Add to history (using a sorted set with timestamp as score)
            String historyKey = STATUS_HISTORY_KEY_PREFIX + requestId;
            double score = status.getUpdatedAt().toEpochSecond(java.time.ZoneOffset.UTC);
            redisTemplate.opsForZSet().add(historyKey, statusJson, score);
            
            // Add to index of all requests
            redisTemplate.opsForSet().add(STATUS_INDEX_KEY, requestId);
            
            // Add to status type index
            String statusType = status.getStatus();
            String statusTypeKey = STATUS_BY_TYPE_PREFIX + statusType;
            redisTemplate.opsForSet().add(statusTypeKey, requestId);
            
            log.debug("Saved status for request {}: {}", requestId, status.getStatus());
        } catch (Exception e) {
            log.error("Error saving request status", e);
        }
    }

    /**
     * Get the current status of a request
     */
    public RequestStatus getCurrentStatus(String requestId) {
        try {
            String key = STATUS_KEY_PREFIX + requestId;
            String json = redisTemplate.opsForValue().get(key);
            
            if (json != null) {
                return objectMapper.readValue(json, RequestStatus.class);
            }
        } catch (Exception e) {
            log.error("Error retrieving current status for request {}", requestId, e);
        }
        return null;
    }

    /**
     * Get the status history for a request
     */
    public List<RequestStatus> getStatusHistory(String requestId) {
        try {
            String historyKey = STATUS_HISTORY_KEY_PREFIX + requestId;
            Set<String> jsonEntries = redisTemplate.opsForZSet().range(historyKey, 0, -1);
            
            if (jsonEntries == null || jsonEntries.isEmpty()) {
                return Collections.emptyList();
            }
            
            List<RequestStatus> history = new ArrayList<>(jsonEntries.size());
            for (String json : jsonEntries) {
                history.add(objectMapper.readValue(json, RequestStatus.class));
            }
            
            return history;
        } catch (Exception e) {
            log.error("Error retrieving status history for request {}", requestId, e);
            return Collections.emptyList();
        }
    }

    /**
     * Get all request IDs in the system
     */
    public Set<String> getAllRequestIds() {
        return redisTemplate.opsForSet().members(STATUS_INDEX_KEY);
    }
    
    /**
     * Get all request IDs with a specific status
     */
    public Set<String> getRequestIdsByStatus(String status) {
        String key = STATUS_BY_TYPE_PREFIX + status;
        return redisTemplate.opsForSet().members(key);
    }

    /**
     * Get current status for all requests
     */
    public List<RequestStatus> getAllCurrentStatuses() {
        try {
            Set<String> requestIds = getAllRequestIds();
            if (requestIds == null || requestIds.isEmpty()) {
                return Collections.emptyList();
            }
            
            List<RequestStatus> statuses = new ArrayList<>(requestIds.size());
            for (String requestId : requestIds) {
                RequestStatus status = getCurrentStatus(requestId);
                if (status != null) {
                    statuses.add(status);
                }
            }
            
            return statuses;
        } catch (Exception e) {
            log.error("Error retrieving all current statuses", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Get current status for all requests with specific status
     */
    public List<RequestStatus> getStatusesByType(String statusType) {
        try {
            Set<String> requestIds = getRequestIdsByStatus(statusType);
            if (requestIds == null || requestIds.isEmpty()) {
                return Collections.emptyList();
            }
            
            return requestIds.stream()
                .map(this::getCurrentStatus)
                .filter(status -> status != null)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error retrieving statuses by type {}", statusType, e);
            return Collections.emptyList();
        }
    }
} 