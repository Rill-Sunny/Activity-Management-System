package com.limitactivitysystem.identity.dto;

public record CurrentUserResponse(Long id, String username, String role, String status) {
}
