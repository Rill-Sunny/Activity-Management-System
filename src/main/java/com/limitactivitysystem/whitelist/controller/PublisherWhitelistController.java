package com.limitactivitysystem.whitelist.controller;

import com.limitactivitysystem.whitelist.dto.AddWhitelistMemberRequest;
import com.limitactivitysystem.whitelist.dto.WhitelistConfigResponse;
import com.limitactivitysystem.whitelist.dto.WhitelistMemberResponse;
import com.limitactivitysystem.whitelist.model.WhitelistMember;
import com.limitactivitysystem.whitelist.service.PublisherWhitelistService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.ACTIVITY;
import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.SESSION;

@RestController
@RequestMapping("/publisher")
public class PublisherWhitelistController {
    private final PublisherWhitelistService service;

    public PublisherWhitelistController(PublisherWhitelistService service) { this.service = service; }

    @PutMapping("/activities/{activityId}/whitelist")
    public WhitelistConfigResponse enableActivity(@PathVariable("activityId") Long activityId) {
        return WhitelistConfigResponse.from(service.enable(ACTIVITY, activityId));
    }

    @PutMapping("/sessions/{sessionId}/whitelist")
    public WhitelistConfigResponse enableSession(@PathVariable("sessionId") Long sessionId) {
        return WhitelistConfigResponse.from(service.enable(SESSION, sessionId));
    }

    @PostMapping("/activities/{activityId}/whitelist/members")
    @ResponseStatus(HttpStatus.CREATED)
    public WhitelistMemberResponse addActivityMember(@PathVariable("activityId") Long activityId,
                                                      @Valid @RequestBody AddWhitelistMemberRequest request) {
        WhitelistMember member = service.addMember(ACTIVITY, activityId, request.getUserId());
        return new WhitelistMemberResponse(member.getMemberId(), member.getUserId());
    }

    @PostMapping("/sessions/{sessionId}/whitelist/members")
    @ResponseStatus(HttpStatus.CREATED)
    public WhitelistMemberResponse addSessionMember(@PathVariable("sessionId") Long sessionId,
                                                     @Valid @RequestBody AddWhitelistMemberRequest request) {
        WhitelistMember member = service.addMember(SESSION, sessionId, request.getUserId());
        return new WhitelistMemberResponse(member.getMemberId(), member.getUserId());
    }

    @DeleteMapping("/activities/{activityId}/whitelist/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeActivityMember(@PathVariable("activityId") Long activityId, @PathVariable("userId") Long userId) {
        service.removeMember(ACTIVITY, activityId, userId);
    }

    @DeleteMapping("/sessions/{sessionId}/whitelist/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSessionMember(@PathVariable("sessionId") Long sessionId, @PathVariable("userId") Long userId) {
        service.removeMember(SESSION, sessionId, userId);
    }

    @DeleteMapping("/activities/{activityId}/whitelist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableActivity(@PathVariable("activityId") Long activityId) { service.disable(ACTIVITY, activityId); }

    @DeleteMapping("/sessions/{sessionId}/whitelist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableSession(@PathVariable("sessionId") Long sessionId) { service.disable(SESSION, sessionId); }
}
