package com.limitactivitysystem.messaging;

import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {
    private final CurrentUserProvider currentUser;
    private final NotificationMapper notifications;

    public NotificationController(CurrentUserProvider currentUser, NotificationMapper notifications) {
        this.currentUser = currentUser;
        this.notifications = notifications;
    }

    @GetMapping
    public List<NotificationMapper.NotificationRecord> list(
            @RequestParam(defaultValue = "50") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return notifications.findForUser(currentUser.getAuthenticatedUserId(), boundedLimit);
    }
}
