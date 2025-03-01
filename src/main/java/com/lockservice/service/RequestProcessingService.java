package com.lockservice.service;

import com.lockservice.model.RequestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class RequestProcessingService {

    private final RequestStatusProducer statusProducer;
    private final RequestStatusConsumer statusConsumer;
    private final RequestStatusMonitor statusMonitor;
    private final ScheduledExecutorService executorService;
    
    @Autowired
    public RequestProcessingService(
            RequestStatusProducer statusProducer,
            RequestStatusConsumer statusConsumer,
            RequestStatusMonitor statusMonitor) {
        this.statusProducer = statusProducer;
        this.statusConsumer = statusConsumer;
        this.statusMonitor = statusMonitor;
        this.executorService = Executors.newScheduledThreadPool(5);
        
        // Register for monitoring events
        this.statusMonitor.addMonitoringListener(this::handleMonitoringEvent);
    }
    
    /**
     * Process a request asynchronously with status tracking
     */
    public String processRequest(String userId, Map<String, Object> requestData, int totalSteps) {
        // Generate unique request ID
        String requestId = UUID.randomUUID().toString();
        
        // Create initial metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "ASYNC_PROCESS");
        metadata.put("initialData", requestData);
        
        // Publish initial status
        statusProducer.publishStatus(
                requestId,
                "CREATED",
                userId,
                metadata,
                "process-service",
                0,
                totalSteps);
        
        // Start monitoring the request
        statusMonitor.monitorRequest(requestId, 5, "COMPLETED", true);
        
        // Start async processing
        simulateAsyncProcessing(requestId, userId, totalSteps);
        
        return requestId;
    }
    
    /**
     * Subscribe to status updates for a specific request
     */
    public void subscribeToStatusUpdates(String requestId, com.lockservice.model.Consumer<RequestStatus> statusListener) {
        statusConsumer.addStatusChangeListener(requestId, statusListener);
    }
    
    /**
     * Unsubscribe from status updates
     */
    public void unsubscribeFromStatusUpdates(String requestId, com.lockservice.model.Consumer<RequestStatus> statusListener) {
        statusConsumer.removeStatusChangeListener(requestId, statusListener);
    }
    
    /**
     * Get current status of a request
     */
    public RequestStatus getRequestStatus(String requestId) {
        return statusConsumer.getLatestStatus(requestId);
    }
    
    /**
     * Simulate async processing with status updates
     */
    private void simulateAsyncProcessing(String requestId, String userId, int totalSteps) {
        CompletableFuture.runAsync(() -> {
            try {
                // Update to IN_PROGRESS
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("processStartTime", System.currentTimeMillis());
                
                statusProducer.publishStatus(
                        requestId,
                        "IN_PROGRESS",
                        userId,
                        metadata,
                        "process-service",
                        1,
                        totalSteps);
                
                // Simulate processing steps
                for (int step = 1; step <= totalSteps; step++) {
                    int currentStep = step;
                    
                    // Use scheduled executor to delay updates
                    executorService.schedule(() -> {
                        try {
                            if (currentStep == totalSteps) {
                                // Last step - complete the process
                                Map<String, Object> finalMetadata = new HashMap<>();
                                finalMetadata.put("processingTime", System.currentTimeMillis());
                                finalMetadata.put("result", "Processing completed successfully");
                                
                                statusProducer.publishStatus(
                                        requestId,
                                        "COMPLETED",
                                        userId,
                                        finalMetadata,
                                        "process-service",
                                        totalSteps,
                                        totalSteps);
                            } else {
                                // Update progress
                                Map<String, Object> progressMetadata = new HashMap<>();
                                progressMetadata.put("stepDescription", "Processing step " + currentStep);
                                
                                statusProducer.publishStatus(
                                        requestId,
                                        "IN_PROGRESS",
                                        userId,
                                        progressMetadata,
                                        "process-service",
                                        currentStep,
                                        totalSteps);
                            }
                        } catch (Exception e) {
                            log.error("Error updating status during processing", e);
                            
                            // Publish error status
                            Map<String, Object> errorMetadata = new HashMap<>();
                            errorMetadata.put("error", e.getMessage());
                            errorMetadata.put("step", currentStep);
                            
                            statusProducer.publishStatus(
                                    requestId,
                                    "ERROR",
                                    userId,
                                    errorMetadata,
                                    "process-service",
                                    currentStep,
                                    totalSteps);
                        }
                    }, currentStep * 2, TimeUnit.SECONDS);
                }
                
            } catch (Exception e) {
                // Handle error
                Map<String, Object> errorMetadata = new HashMap<>();
                errorMetadata.put("error", e.getMessage());
                
                statusProducer.publishStatus(
                        requestId,
                        "FAILED",
                        userId,
                        errorMetadata,
                        "process-service",
                        0,
                        totalSteps);
                
                log.error("Error in async processing", e);
            }
        });
    }
    
    /**
     * Simulate async processing with status updates, starting from a specific step
     */
    private void simulateAsyncProcessing(String requestId, String userId, int totalSteps, int startStep) {
        CompletableFuture.runAsync(() -> {
            try {
                // Update to IN_PROGRESS
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("processStartTime", System.currentTimeMillis());
                metadata.put("resumedFromStep", startStep);
                
                statusProducer.publishStatus(
                        requestId,
                        "IN_PROGRESS",
                        userId,
                        metadata,
                        "process-service",
                        startStep,
                        totalSteps);
                
                // Simulate processing steps (starting from startStep)
                for (int step = startStep; step <= totalSteps; step++) {
                    int currentStep = step;
                    
                    // Use scheduled executor to delay updates
                    executorService.schedule(() -> {
                        try {
                            if (currentStep == totalSteps) {
                                // Last step - complete the process
                                Map<String, Object> finalMetadata = new HashMap<>();
                                finalMetadata.put("processingTime", System.currentTimeMillis());
                                finalMetadata.put("result", "Processing completed successfully");
                                
                                statusProducer.publishStatus(
                                        requestId,
                                        "COMPLETED",
                                        userId,
                                        finalMetadata,
                                        "process-service",
                                        totalSteps,
                                        totalSteps);
                            } else {
                                // Update progress
                                Map<String, Object> progressMetadata = new HashMap<>();
                                progressMetadata.put("stepDescription", "Processing step " + currentStep);
                                
                                statusProducer.publishStatus(
                                        requestId,
                                        "IN_PROGRESS",
                                        userId,
                                        progressMetadata,
                                        "process-service",
                                        currentStep,
                                        totalSteps);
                            }
                        } catch (Exception e) {
                            log.error("Error updating status during processing", e);
                            
                            // Publish error status
                            Map<String, Object> errorMetadata = new HashMap<>();
                            errorMetadata.put("error", e.getMessage());
                            errorMetadata.put("step", currentStep);
                            
                            statusProducer.publishStatus(
                                    requestId,
                                    "ERROR",
                                    userId,
                                    errorMetadata,
                                    "process-service",
                                    currentStep,
                                    totalSteps);
                        }
                    }, (currentStep - startStep + 1) * 2, TimeUnit.SECONDS);
                }
                
            } catch (Exception e) {
                // Handle error
                Map<String, Object> errorMetadata = new HashMap<>();
                errorMetadata.put("error", e.getMessage());
                
                statusProducer.publishStatus(
                        requestId,
                        "FAILED",
                        userId,
                        errorMetadata,
                        "process-service",
                        0,
                        totalSteps);
                
                log.error("Error in async processing", e);
            }
        });
    }
    
    /**
     * Retry a failed request
     */
    public boolean retryRequest(String requestId) {
        RequestStatus status = statusConsumer.getLatestStatus(requestId);
        if (status == null) {
            log.warn("Cannot retry request {} - status not found", requestId);
            return false;
        }
        
        if (!"FAILED".equals(status.getStatus()) && !"ERROR".equals(status.getStatus())) {
            log.warn("Cannot retry request {} - status is not FAILED or ERROR", requestId);
            return false;
        }
        
        // Update status to RETRYING
        Map<String, Object> metadata = new HashMap<>();
        if (status.getMetadata() != null) {
            metadata.putAll(status.getMetadata());
        }
        metadata.put("retryAttempt", metadata.containsKey("retryAttempt") 
                ? ((Integer)metadata.get("retryAttempt")) + 1 : 1);
        metadata.put("retryTime", System.currentTimeMillis());
        
        statusProducer.publishStatus(
                requestId,
                "RETRYING",
                status.getUserId(),
                metadata,
                "process-service",
                status.getProgress(),
                status.getTotalSteps());
        
        // Start async processing from the failed step
        int startStep = status.getProgress() != null ? status.getProgress() : 0;
        simulateAsyncProcessing(requestId, status.getUserId(), status.getTotalSteps(), startStep);
        
        return true;
    }
    
    /**
     * Cancel a request that is in progress
     */
    public boolean cancelRequest(String requestId, String userId, String reason) {
        RequestStatus status = statusConsumer.getLatestStatus(requestId);
        if (status == null) {
            log.warn("Cannot cancel request {} - status not found", requestId);
            return false;
        }
        
        // Only allow cancellation of non-terminal states
        if ("COMPLETED".equals(status.getStatus()) || 
            "FAILED".equals(status.getStatus()) || 
            "CANCELLED".equals(status.getStatus())) {
            log.warn("Cannot cancel request {} - already in terminal state: {}", 
                    requestId, status.getStatus());
            return false;
        }
        
        // Update status to CANCELLED
        Map<String, Object> metadata = new HashMap<>();
        if (status.getMetadata() != null) {
            metadata.putAll(status.getMetadata());
        }
        metadata.put("cancelReason", reason);
        metadata.put("cancelTime", System.currentTimeMillis());
        
        statusProducer.publishStatus(
                requestId,
                "CANCELLED",
                userId,
                metadata,
                "process-service",
                status.getProgress(),
                status.getTotalSteps());
        
        // Stop monitoring this request
        statusMonitor.stopMonitoring(requestId);
        
        log.info("Request {} cancelled by user {}: {}", requestId, userId, reason);
        return true;
    }
    
    /**
     * Handle monitoring events
     */
    private void handleMonitoringEvent(RequestStatusMonitor.MonitoringEvent event) {
        switch (event.getType()) {
            case STUCK:
                log.warn("Request processing stuck: {}", event.getMessage());
                // Implement recovery actions here
                handleStuckRequest(event.getRequestId(), event.getStatus());
                break;
                
            case COMPLETED:
                log.info("Request processing completed: {}", event.getRequestId());
                // Trigger follow-up actions here
                handleCompletedRequest(event.getRequestId(), event.getStatus());
                break;
                
            case ALERT:
                log.warn("Request processing alert: {}", event.getMessage());
                // Handle alerts
                break;
                
            case TIMEOUT:
                log.warn("Request monitoring timed out: {}", event.getMessage());
                // Handle timeout
                break;
                
            default:
                log.debug("Monitoring event received: {}", event.getType());
                break;
        }
    }
    
    /**
     * Handle a stuck request
     */
    private void handleStuckRequest(String requestId, RequestStatus status) {
        // Example: Increment retry counter in metadata
        Map<String, Object> metadata = new HashMap<>();
        if (status.getMetadata() != null) {
            metadata.putAll(status.getMetadata());
        }
        
        int stuckCount = metadata.containsKey("stuckCount") 
                ? ((Number)metadata.get("stuckCount")).intValue() + 1 : 1;
        metadata.put("stuckCount", stuckCount);
        
        // If stuck too many times, mark as failed
        if (stuckCount >= 3) {
            metadata.put("failureReason", "Request stuck too many times");
            
            statusProducer.publishStatus(
                    requestId,
                    "FAILED",
                    status.getUserId(),
                    metadata,
                    "monitor-service",
                    status.getProgress(),
                    status.getTotalSteps());
            
            // Stop monitoring
            statusMonitor.stopMonitoring(requestId);
        } else {
            // Just update metadata
            statusProducer.publishStatus(
                    requestId,
                    status.getStatus(),
                    status.getUserId(),
                    metadata,
                    "monitor-service",
                    status.getProgress(),
                    status.getTotalSteps());
        }
    }
    
    /**
     * Handle a completed request
     */
    private void handleCompletedRequest(String requestId, RequestStatus status) {
        // Example: Perform follow-up actions like sending notifications
        log.info("Performing follow-up actions for completed request: {}", requestId);
        
        // Update metadata to record completion handling
        Map<String, Object> metadata = new HashMap<>();
        if (status.getMetadata() != null) {
            metadata.putAll(status.getMetadata());
        }
        metadata.put("completionHandled", true);
        metadata.put("completionHandledTime", System.currentTimeMillis());
        
        statusProducer.publishStatus(
                requestId,
                status.getStatus(),
                status.getUserId(),
                metadata,
                "monitor-service",
                status.getProgress(),
                status.getTotalSteps());
    }
    
    /**
     * Cleanup method to shutdown executor when application stops
     */
    public void shutdown() {
        try {
            log.info("Shutting down request processing service executor");
            executorService.shutdown();
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
} 