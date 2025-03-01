package com.lockservice.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lockservice.event.LockEventPublisher;
import com.lockservice.exception.LockAlreadyExistsException;
import com.lockservice.model.Lock;
import com.lockservice.model.LockStatus;
import com.lockservice.repository.LockRepository;

@Service
public class LockServiceImpl implements LockService {

    private final LockRepository lockRepository;
    private final LockEventPublisher lockEventPublisher;
    
    @Value("${lock.expiration.seconds}")
    private long lockExpirationSeconds;

    @Autowired
    public LockServiceImpl(LockRepository lockRepository, LockEventPublisher lockEventPublisher) {
        this.lockRepository = lockRepository;
        this.lockEventPublisher = lockEventPublisher;
    }

    @Override
    public Lock acquireLock(String requestId, String userId, String metadata) {
        Optional<Lock> existingLock = lockRepository.findLockByRequestId(requestId);
        
        if (existingLock.isPresent()) {
            Lock lock = existingLock.get();
            
            // Check if the lock is expired or already released
            if (lock.getExpiresAt().isAfter(LocalDateTime.now()) && 
                lock.getStatus() != LockStatus.RELEASED) {
                throw new LockAlreadyExistsException(
                    "Request is already locked", lock.getUserId());
            }
            
            // Lock is expired or released, we can override it
            lockRepository.deleteLock(requestId);
        }
        
        LocalDateTime now = LocalDateTime.now();
        Lock newLock = Lock.builder()
                .requestId(requestId)
                .userId(userId)
                .status(LockStatus.START)
                .lockedAt(now)
                .updatedAt(now)
                .expiresAt(now.plusSeconds(lockExpirationSeconds))
                .metadata(metadata)
                .build();
        
        boolean saved = lockRepository.saveLock(newLock);
        
        if (!saved) {
            // Race condition, another request acquired the lock first
            Optional<Lock> raceLock = lockRepository.findLockByRequestId(requestId);
            if (raceLock.isPresent()) {
                throw new LockAlreadyExistsException(
                    "Request was locked by another user", raceLock.get().getUserId());
            }
        }
        
        // Publish event for real-time updates
        lockEventPublisher.publishLockUpdate(newLock);
        
        return newLock;
    }

    @Override
    public Lock updateLockStatus(String requestId, String userId, LockStatus status) {
        Optional<Lock> existingLock = lockRepository.findLockByRequestId(requestId);
        
        if (existingLock.isEmpty()) {
            throw new IllegalStateException("Lock does not exist for request ID: " + requestId);
        }
        
        Lock lock = existingLock.get();
        
        // Verify the user owns the lock
        if (!lock.getUserId().equals(userId)) {
            throw new IllegalStateException("Lock is owned by a different user");
        }
        
        // Update the lock
        lock.setStatus(status);
        lock.setUpdatedAt(LocalDateTime.now());
        
        lockRepository.updateLock(lock);
        
        // Publish event for real-time updates
        lockEventPublisher.publishLockUpdate(lock);
        
        return lock;
    }

    @Override
    public boolean releaseLock(String requestId, String userId) {
        boolean released = lockRepository.releaseLock(requestId, userId);
        
        if (released) {
            // Get updated lock to publish event
            lockRepository.findLockByRequestId(requestId).ifPresent(lockEventPublisher::publishLockUpdate);
        }
        
        return released;
    }

    @Override
    public Optional<Lock> getLockInfo(String requestId) {
        return lockRepository.findLockByRequestId(requestId);
    }
} 