package com.lockservice.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.lockservice.model.Lock;

@Component
public class LockEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public LockEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishLockUpdate(Lock lock) {
        // Send WebSocket notification
        messagingTemplate.convertAndSend("/topic/locks/" + lock.getRequestId(), lock);
        
        // Also send to a general topic for monitoring purposes
        messagingTemplate.convertAndSend("/topic/locks", lock);
    }
} 