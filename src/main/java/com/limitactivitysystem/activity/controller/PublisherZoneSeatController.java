package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.dto.CreateZoneSeatsRequest;
import com.limitactivitysystem.activity.dto.CreateZoneSeatsResponse;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.service.PublisherZoneSeatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/publisher/zones/{zoneId}/seats")
public class PublisherZoneSeatController {

    private final PublisherZoneSeatService seatService;

    public PublisherZoneSeatController(PublisherZoneSeatService seatService) {
        this.seatService = seatService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateZoneSeatsResponse createSeats(@PathVariable("zoneId") Long zoneId,
                                               @Valid @RequestBody CreateZoneSeatsRequest request) {
        List<SessionSeat> seats = seatService.createSeats(zoneId, request);
        return new CreateZoneSeatsResponse(seats.size(), seats.stream().map(SessionSeat::getSeatId).toList());
    }
}
