package com.lockservice.service;

import java.util.Optional;

import com.lockservice.model.Lock;
import com.lockservice.model.LockStatus;

public interface LockService {
    Lock acquireLock(String requestId, String userId, String metadata);
    Lock updateLockStatus(String requestId, String userId, LockStatus status);
    boolean releaseLock(String requestId, String userId);
    Optional<Lock> getLockInfo(String requestId);
} 