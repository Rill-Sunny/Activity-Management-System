package com.limitactivitysystem.whitelist.dto;

import com.limitactivitysystem.whitelist.model.WhitelistConfig;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;

public record WhitelistConfigResponse(Long configId, WhitelistScopeType scopeType, Long scopeId) {
    public static WhitelistConfigResponse from(WhitelistConfig config) {
        return new WhitelistConfigResponse(config.getConfigId(), config.getScopeType(), config.getScopeId());
    }
}
