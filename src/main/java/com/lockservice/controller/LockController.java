package com.lockservice.controller;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lockservice.model.ApiResponse;
import com.lockservice.model.Lock;
import com.lockservice.model.LockStatus;
import com.lockservice.service.LockService;

@RestController
@RequestMapping("/api/locks")
public class LockController {

    private final LockService lockService;

    @Autowired
    public LockController(LockService lockService) {
        this.lockService = lockService;
    }

    @PostMapping("/{requestId}")
    public ResponseEntity<ApiResponse<Lock>> acquireLock(
            @PathVariable String requestId,
            @RequestParam String userId,
            @RequestParam(required = false) String metadata) {
        
        Lock lock = lockService.acquireLock(requestId, userId, metadata);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Lock acquired successfully", lock));
    }

    @PutMapping("/{requestId}/status")
    public ResponseEntity<ApiResponse<Lock>> updateLockStatus(
            @PathVariable String requestId,
            @RequestParam String userId,
            @RequestParam LockStatus status) {
        
        Lock lock = lockService.updateLockStatus(requestId, userId, status);
        return ResponseEntity.ok(ApiResponse.success("Lock status updated", lock));
    }

    @PutMapping("/{requestId}/release")
    public ResponseEntity<ApiResponse<Object>> releaseLock(
            @PathVariable String requestId,
            @RequestParam String userId) {
        
        boolean released = lockService.releaseLock(requestId, userId);
        
        if (released) {
            return ResponseEntity.ok(ApiResponse.success("Lock released successfully", null));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Failed to release lock. Either it doesn't exist or belongs to another user."));
        }
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<Lock>> getLockInfo(@PathVariable String requestId) {
        Optional<Lock> lock = lockService.getLockInfo(requestId);
        
        return lock.map(l -> ResponseEntity.ok(ApiResponse.success(l)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Lock not found for requestId: " + requestId)));
    }
} 