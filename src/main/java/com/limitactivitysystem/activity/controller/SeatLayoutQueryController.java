package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.dto.SeatLayoutResponse;
import com.limitactivitysystem.activity.service.SeatLayoutQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
public class SeatLayoutQueryController {
    private final SeatLayoutQueryService layouts;

    public SeatLayoutQueryController(SeatLayoutQueryService layouts) {
        this.layouts = layouts;
    }

    @GetMapping("/{sessionId}/seat-layout")
    public SeatLayoutResponse get(@PathVariable Long sessionId) {
        return layouts.get(sessionId);
    }
}
