package com.limitactivitysystem.whitelist.model;

import java.time.LocalDateTime;

public class WhitelistConfig {
    private Long configId;
    private WhitelistScopeType scopeType;
    private Long scopeId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public WhitelistScopeType getScopeType() { return scopeType; }
    public void setScopeType(WhitelistScopeType scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
