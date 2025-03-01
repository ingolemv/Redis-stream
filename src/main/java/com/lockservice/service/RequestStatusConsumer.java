package com.lockservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockservice.model.RequestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class RequestStatusConsumer implements StreamListener<String, MapRecord<String, Object, Object>> {

    @Value("${redis.stream.status-key:request-status-stream}")
    private String streamKey;
    
    @Value("${redis.stream.status-consumer-group:request-status-group}")
    private String consumerGroup;
    
    @Value("${redis.stream.status-consumer-name:request-status-consumer}")
    private String consumerName;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RequestStatusRepository statusRepository;
    
    // In-memory cache of the latest status for each request
    private final Map<String, RequestStatus> latestStatuses = new ConcurrentHashMap<>();
    
    // Callbacks for status changes
    private final Map<String, List<java.util.function.Consumer<RequestStatus>>> statusChangeListeners = new ConcurrentHashMap<>();
    
    // Global listeners for all status updates
    private final List<com.lockservice.model.Consumer<RequestStatus>> globalListeners = new CopyOnWriteArrayList<>();

    // Add as a field
    private final Map<String, List<com.lockservice.model.Consumer<RequestStatus>>> statusListeners = new ConcurrentHashMap<>();

    @Autowired
    public RequestStatusConsumer(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            RequestStatusRepository statusRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.statusRepository = statusRepository;
    }

    @PostConstruct
    public void init() {
        try {
            // Create consumer group if not exists
            redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
        } catch (Exception e) {
            log.info("Consumer group already exists or couldn't be created: {}", e.getMessage());
        }
        
        // Load initial statuses
        loadCurrentStatuses();
    }
    
    private void loadCurrentStatuses() {
        try {
            List<RequestStatus> allStatuses = statusRepository.getAllCurrentStatuses();
            for (RequestStatus status : allStatuses) {
                latestStatuses.put(status.getRequestId(), status);
            }
            log.info("Loaded {} current request statuses into memory", latestStatuses.size());
        } catch (Exception e) {
            log.error("Error loading current statuses", e);
        }
    }

    @SuppressWarnings("unchecked")
    @Scheduled(fixedDelay = 1000)
    public void consumeEvents() {
        try {
            // Read new records from the stream
            redisTemplate.opsForStream()
                .read(org.springframework.data.redis.connection.stream.Consumer.from(consumerGroup, consumerName),
                      StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                .forEach(this::onMessage);
        } catch (Exception e) {
            log.error("Error consuming stream events", e);
        }
    }

    @Override
    public void onMessage(MapRecord<String, Object, Object> message) {
        try {
            // Extract message content
            Map<Object, Object> values = message.getValue();
            
            // Convert to RequestStatus object
            RequestStatus status = mapToRequestStatus(values);
            
            // Process the status update
            processStatusUpdate(status);
            
            // Store in repository (if not already done by producer)
            statusRepository.saveStatus(status);
            
            // Update in-memory cache
            RequestStatus previousStatus = latestStatuses.put(status.getRequestId(), status);
            
            // Notify both sets of listeners
            notifyStatusListeners(status, previousStatus);
            
            // Acknowledge processing
            redisTemplate.opsForStream().acknowledge(consumerGroup, message);
            
            log.info("Processed status update for request {}: {}", 
                    status.getRequestId(), status.getStatus());
            
        } catch (Exception e) {
            log.error("Error processing status message", e);
        }
    }
    
    private void notifyStatusListeners(RequestStatus currentStatus, RequestStatus previousStatus) {
        String requestId = currentStatus.getRequestId();
        
        // Notify specific listeners for this request
        if (statusChangeListeners.containsKey(requestId)) {
            for (java.util.function.Consumer<RequestStatus> listener : statusChangeListeners.get(requestId)) {
                try {
                    listener.accept(currentStatus);
                } catch (Exception e) {
                    log.error("Error in status change listener for request {}", requestId, e);
                }
            }
        }
        
        // Notify global listeners
        for (com.lockservice.model.Consumer<RequestStatus> listener : globalListeners) {
            try {
                listener.accept(currentStatus);
            } catch (Exception e) {
                log.error("Error in global status change listener", e);
            }
        }
        
        // Also notify the other set of listeners
        List<com.lockservice.model.Consumer<RequestStatus>> listeners = statusListeners.get(requestId);
        if (listeners != null) {
            listeners.forEach(listener -> listener.accept(currentStatus));
        }
    }

    private RequestStatus mapToRequestStatus(Map<Object, Object> values) throws Exception {
        RequestStatus status = new RequestStatus();
        
        // Extract fields from Redis stream record
        status.setRequestId(values.get("requestId").toString());
        status.setStatus(values.get("status").toString());
        status.setUserId(values.get("userId").toString());
        status.setSource(values.get("source").toString());
        status.setCreatedAt(LocalDateTime.parse(values.get("createdAt").toString()));
        status.setUpdatedAt(LocalDateTime.parse(values.get("updatedAt").toString()));
        
        // Optional fields
        if (values.containsKey("progress") && values.get("progress") != null) {
            status.setProgress(Integer.parseInt(values.get("progress").toString()));
        }
        if (values.containsKey("totalSteps") && values.get("totalSteps") != null) {
            status.setTotalSteps(Integer.parseInt(values.get("totalSteps").toString()));
        }
        if (values.containsKey("metadata") && values.get("metadata") != null) {
            String metadataJson = values.get("metadata").toString();
            Map<String, Object> metadata = objectMapper.readValue(
                    metadataJson, new TypeReference<Map<String, Object>>() {});
            status.setMetadata(metadata);
        }
        
        return status;
    }

    private void processStatusUpdate(RequestStatus status) {
        // Custom processing logic can be implemented here
        // For example: trigger notifications, update statistics, etc.
        
        // Example: Log completion of requests
        if ("COMPLETED".equals(status.getStatus())) {
            log.info("Request {} has completed processing", status.getRequestId());
            // You could trigger additional workflows here
        }
    }

    /**
     * Get the latest status for a specific request
     */
    public RequestStatus getLatestStatus(String requestId) {
        // Try from memory cache first
        RequestStatus cachedStatus = latestStatuses.get(requestId);
        if (cachedStatus != null) {
            return cachedStatus;
        }
        
        // If not in cache, try from repository
        RequestStatus repositoryStatus = statusRepository.getCurrentStatus(requestId);
        if (repositoryStatus != null) {
            // Update cache
            latestStatuses.put(requestId, repositoryStatus);
        }
        
        return repositoryStatus;
    }

    /**
     * Get all statuses currently in memory
     */
    public Map<String, RequestStatus> getAllLatestStatuses() {
        return new ConcurrentHashMap<>(latestStatuses);
    }
    
    /**
     * Register a listener for a specific request's status changes
     */
    public void addStatusChangeListener(String requestId, com.lockservice.model.Consumer<RequestStatus> listener) {
        statusListeners.computeIfAbsent(requestId, k -> new CopyOnWriteArrayList<>()).add(listener);
    }
    
    /**
     * Remove a listener for a specific request
     */
    public void removeStatusChangeListener(String requestId, com.lockservice.model.Consumer<RequestStatus> listener) {
        if (statusListeners.containsKey(requestId)) {
            statusListeners.get(requestId).remove(listener);
        }
    }
    
    /**
     * Register a global listener for all status changes
     */
    public void addGlobalStatusListener(com.lockservice.model.Consumer<RequestStatus> listener) {
        globalListeners.add(listener);
    }
    
    /**
     * Remove a global listener
     */
    public void removeGlobalStatusListener(com.lockservice.model.Consumer<RequestStatus> listener) {
        globalListeners.remove(listener);
    }
} 