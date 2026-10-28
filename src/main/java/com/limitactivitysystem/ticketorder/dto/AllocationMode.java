package com.limitactivitysystem.ticketorder.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AllocationMode {
    SELECT_SEAT,
    AUTO_ASSIGN;

    @JsonCreator
    public static AllocationMode fromJson(String value) {
        return valueOf(value);
    }
}
