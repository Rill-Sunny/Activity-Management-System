package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import org.springframework.stereotype.Component;

@Component
public class ActivitySessionConfigurationValidator {

    // Shared by session creation and publication while the parent activity is locked.
    public void validate(Activity activity, ActivitySession session) {
        if (session.getStartTime() == null || session.getEndTime() == null
                || !session.getStartTime().isBefore(session.getEndTime())) {
            throw new ActivityException(ActivityErrorCode.INVALID_SESSION_CONFIG, "startTime must be earlier than endTime");
        }
        if (session.getCapacity() == null || session.getCapacity() <= 0) {
            throw new ActivityException(ActivityErrorCode.INVALID_CAPACITY, "capacity must be positive");
        }

        if ("PAID".equals(activity.getType())) {
            Integer limit = session.getMaxTicketsPerUser();
            if (limit == null || limit <= 0 || limit > session.getCapacity()) {
                throw new ActivityException(ActivityErrorCode.INVALID_SESSION_CONFIG,
                        "PAID sessions require maxTicketsPerUser between 1 and capacity");
            }
            if (!"SELECT_SEAT".equals(session.getSeatMode()) && !"AUTO_ASSIGN".equals(session.getSeatMode())) {
                throw new ActivityException(ActivityErrorCode.INVALID_SESSION_CONFIG,
                        "PAID sessions require SELECT_SEAT or AUTO_ASSIGN");
            }
        } else if ("FREE".equals(activity.getType())) {
            if (session.getMaxTicketsPerUser() != null || session.getSeatMode() != null) {
                throw new ActivityException(ActivityErrorCode.INVALID_SESSION_CONFIG,
                        "FREE sessions require null maxTicketsPerUser and seatMode");
            }
        } else {
            throw new ActivityException(ActivityErrorCode.INVALID_ACTIVITY_TYPE, "Unsupported activity type");
        }
    }
}
