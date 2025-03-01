package com.lockservice.service;

import com.lockservice.model.RequestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.Data;

@Service
@Slf4j
public class RequestStatusMonitor {

    private final RequestStatusRepository statusRepository;
    private final RequestStatusProducer statusProducer;
    private final RequestStatusConsumer statusConsumer;
    
    // Track monitored requests with their alert thresholds
    private final Map<String, MonitorConfig> monitoredRequests = new ConcurrentHashMap<>();
    
    // Callbacks for monitoring events
    private final List<Consumer<MonitoringEvent>> monitoringListeners = new ArrayList<>();

    @Autowired
    public RequestStatusMonitor(
            RequestStatusRepository statusRepository,
            RequestStatusProducer statusProducer,
            RequestStatusConsumer statusConsumer) {
        this.statusRepository = statusRepository;
        this.statusProducer = statusProducer;
        this.statusConsumer = statusConsumer;
        
        // Register global status listener to handle monitored requests
        this.statusConsumer.addGlobalStatusListener(this::handleStatusUpdate);
    }
    
    /**
     * Handle status updates for monitored requests
     */
    private void handleStatusUpdate(RequestStatus status) {
        String requestId = status.getRequestId();
        
        // If this request is being monitored
        if (monitoredRequests.containsKey(requestId)) {
            MonitorConfig config = monitoredRequests.get(requestId);
            
            // If request has reached its expected final status
            if (config.expectedFinalStatus.equals(status.getStatus())) {
                // Create completion event
                MonitoringEvent event = new MonitoringEvent(
                        MonitoringEventType.COMPLETED,
                        requestId,
                        "Request completed with expected status: " + status.getStatus(),
                        status
                );
                
                // Notify listeners
                notifyMonitoringListeners(event);
                
                // Auto stop monitoring if configured
                if (config.autoStopOnCompletion) {
                    stopMonitoring(requestId);
                }
            }
        }
    }

    /**
     * Start monitoring a request with specific alert thresholds
     */
    public void monitorRequest(String requestId, int stuckThresholdMinutes, 
                              String expectedFinalStatus, boolean autoStopOnCompletion) {
        RequestStatus currentStatus = statusRepository.getCurrentStatus(requestId);
        if (currentStatus == null) {
            log.warn("Cannot monitor request {} - status not found", requestId);
            return;
        }
        
        MonitorConfig config = new MonitorConfig();
        config.stuckThresholdMinutes = stuckThresholdMinutes;
        config.expectedFinalStatus = expectedFinalStatus;
        config.startMonitoringTime = LocalDateTime.now();
        config.autoStopOnCompletion = autoStopOnCompletion;
        config.lastStatus = currentStatus.getStatus();
        config.lastUpdateTime = currentStatus.getUpdatedAt();
        
        monitoredRequests.put(requestId, config);
        
        // Create monitoring event
        MonitoringEvent event = new MonitoringEvent(
                MonitoringEventType.MONITORING_STARTED,
                requestId,
                "Started monitoring request with threshold " + stuckThresholdMinutes + " minutes",
                currentStatus
        );
        
        // Notify listeners
        notifyMonitoringListeners(event);
        
        log.info("Started monitoring request {} with stuck threshold of {} minutes", 
                requestId, stuckThresholdMinutes);
    }
    
    /**
     * Overloaded method with default autoStopOnCompletion=true
     */
    public void monitorRequest(String requestId, int stuckThresholdMinutes, String expectedFinalStatus) {
        monitorRequest(requestId, stuckThresholdMinutes, expectedFinalStatus, true);
    }

    /**
     * Stop monitoring a request
     */
    public void stopMonitoring(String requestId) {
        if (monitoredRequests.containsKey(requestId)) {
            MonitorConfig config = monitoredRequests.remove(requestId);
            
            // Create monitoring event
            RequestStatus currentStatus = statusRepository.getCurrentStatus(requestId);
            MonitoringEvent event = new MonitoringEvent(
                    MonitoringEventType.MONITORING_STOPPED,
                    requestId,
                    "Stopped monitoring request after " + 
                        Duration.between(config.startMonitoringTime, LocalDateTime.now()).toMinutes() + 
                        " minutes",
                    currentStatus
            );
            
            // Notify listeners
            notifyMonitoringListeners(event);
            
            log.info("Stopped monitoring request {}", requestId);
        }
    }

    /**
     * Check if a request is being monitored
     */
    public boolean isMonitored(String requestId) {
        return monitoredRequests.containsKey(requestId);
    }

    /**
     * Get all currently monitored requests
     */
    public Set<String> getMonitoredRequests() {
        return monitoredRequests.keySet();
    }
    
    /**
     * Get monitoring configuration for a request
     */
    public Map<String, Object> getMonitoringDetails(String requestId) {
        if (!monitoredRequests.containsKey(requestId)) {
            return Collections.emptyMap();
        }
        
        MonitorConfig config = monitoredRequests.get(requestId);
        Map<String, Object> details = new HashMap<>();
        details.put("requestId", requestId);
        details.put("stuckThresholdMinutes", config.stuckThresholdMinutes);
        details.put("expectedFinalStatus", config.expectedFinalStatus);
        details.put("startedMonitoringAt", config.startMonitoringTime);
        details.put("lastStatus", config.lastStatus);
        details.put("lastUpdateTime", config.lastUpdateTime);
        
        return details;
    }
    
    /**
     * Add a listener for monitoring events
     */
    public void addMonitoringListener(Consumer<MonitoringEvent> listener) {
        monitoringListeners.add(listener);
    }
    
    /**
     * Remove a monitoring listener
     */
    public void removeMonitoringListener(Consumer<MonitoringEvent> listener) {
        monitoringListeners.remove(listener);
    }
    
    /**
     * Notify all monitoring listeners
     */
    private void notifyMonitoringListeners(MonitoringEvent event) {
        for (Consumer<MonitoringEvent> listener : monitoringListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error in monitoring event listener", e);
            }
        }
    }

    /**
     * Run periodically to check status of all monitored requests
     */
    @Scheduled(fixedDelay = 60000) // Check every minute
    public void checkMonitoredRequests() {
        if (monitoredRequests.isEmpty()) {
            return;
        }
        
        LocalDateTime now = LocalDateTime.now();
        
        for (Map.Entry<String, MonitorConfig> entry : monitoredRequests.entrySet()) {
            String requestId = entry.getKey();
            MonitorConfig config = entry.getValue();
            
            try {
                RequestStatus status = statusRepository.getCurrentStatus(requestId);
                
                if (status == null) {
                    // Create alert event
                    MonitoringEvent event = new MonitoringEvent(
                            MonitoringEventType.ALERT,
                            requestId,
                            "No status found for monitored request",
                            null
                    );
                    notifyMonitoringListeners(event);
                    continue;
                }
                
                // Update config with latest status
                if (!status.getStatus().equals(config.lastStatus)) {
                    config.lastStatus = status.getStatus();
                    config.lastUpdateTime = status.getUpdatedAt();
                    
                    // Create status change event
                    MonitoringEvent event = new MonitoringEvent(
                            MonitoringEventType.STATUS_CHANGED,
                            requestId,
                            "Status changed to: " + status.getStatus(),
                            status
                    );
                    notifyMonitoringListeners(event);
                }
                
                // Check if request is completed with expected status
                if (config.expectedFinalStatus.equals(status.getStatus())) {
                    // Already handled in status update listener
                    continue;
                }
                
                // Check if request is stuck
                Duration duration = Duration.between(status.getUpdatedAt(), now);
                if (duration.toMinutes() > config.stuckThresholdMinutes) {
                    // Create stuck event
                    MonitoringEvent event = new MonitoringEvent(
                            MonitoringEventType.STUCK,
                            requestId,
                            String.format("Request stuck in status %s for %d minutes", 
                                    status.getStatus(), duration.toMinutes()),
                            status
                    );
                    notifyMonitoringListeners(event);
                    
                    // Update metadata to indicate stuck status
                    Map<String, Object> metadata = status.getMetadata() != null 
                            ? new HashMap<>(status.getMetadata()) : new HashMap<>();
                    metadata.put("alert", "Request appears to be stuck");
                    metadata.put("stuckMinutes", duration.toMinutes());
                    
                    statusProducer.publishStatus(
                            requestId, 
                            status.getStatus(), 
                            "system", 
                            metadata,
                            "monitor", 
                            status.getProgress(), 
                            status.getTotalSteps());
                }
                
                // Check if request has been in monitoring too long
                Duration monitoringDuration = Duration.between(config.startMonitoringTime, now);
                if (monitoringDuration.toHours() > 24) { // Auto-stop after 24 hours
                    // Create timeout event
                    MonitoringEvent event = new MonitoringEvent(
                            MonitoringEventType.TIMEOUT,
                            requestId,
                            "Request has been monitored for over 24 hours, stopping monitoring",
                            status
                    );
                    notifyMonitoringListeners(event);
                    
                    stopMonitoring(requestId);
                }
                
            } catch (Exception e) {
                log.error("Error checking monitored request {}", requestId, e);
            }
        }
    }

    /**
     * Configuration for monitoring a specific request
     */
    private static class MonitorConfig {
        int stuckThresholdMinutes;
        String expectedFinalStatus;
        LocalDateTime startMonitoringTime;
        boolean autoStopOnCompletion = true;
        String lastStatus;
        LocalDateTime lastUpdateTime;
    }
    
    /**
     * Event types for monitoring
     */
    public enum MonitoringEventType {
        MONITORING_STARTED,
        MONITORING_STOPPED,
        STATUS_CHANGED,
        STUCK,
        COMPLETED,
        ALERT,
        TIMEOUT
    }
    
    /**
     * Event class for monitoring events
     */
    @Data
    public static class MonitoringEvent {
        private final MonitoringEventType type;
        private final String requestId;
        private final String message;
        private final RequestStatus status;
        private final LocalDateTime timestamp = LocalDateTime.now();

        public MonitoringEventType getType() { return type; }
        public String getRequestId() { return requestId; }
        public String getMessage() { return message; }
        public RequestStatus getStatus() { return status; }
    }
} 