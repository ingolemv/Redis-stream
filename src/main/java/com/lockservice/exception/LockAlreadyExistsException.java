package com.lockservice.exception;

public class LockAlreadyExistsException extends RuntimeException {
    
    private final String lockedBy;
    
    public LockAlreadyExistsException(String message, String lockedBy) {
        super(message);
        this.lockedBy = lockedBy;
    }
    
    public String getLockedBy() {
        return lockedBy;
    }
} 