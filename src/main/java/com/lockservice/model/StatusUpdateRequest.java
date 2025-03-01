package com.lockservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusUpdateRequest {
    private String status;
    private String userId;
    private Map<String, Object> metadata;
    private Integer progress;
    private Integer totalSteps;
} 