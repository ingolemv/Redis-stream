package com.lockservice.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lock implements Serializable {
    private String requestId;
    private String userId;
    private LockStatus status;
    private LocalDateTime lockedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
    private String metadata;
} 