package com.limitactivitysystem.whitelist.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import com.limitactivitysystem.whitelist.mapper.WhitelistConfigMapper;
import com.limitactivitysystem.whitelist.mapper.WhitelistMemberMapper;
import com.limitactivitysystem.whitelist.model.WhitelistConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.ACTIVITY;
import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.SESSION;

@Service
public class QualificationService {
    private final AppUserMapper userMapper;
    private final ActivityMapper activityMapper;
    private final ActivitySessionMapper sessionMapper;
    private final WhitelistConfigMapper configMapper;
    private final WhitelistMemberMapper memberMapper;
    private final CurrentUserProvider currentUserProvider;

    public QualificationService(AppUserMapper userMapper, ActivityMapper activityMapper,
                                ActivitySessionMapper sessionMapper, WhitelistConfigMapper configMapper,
                                WhitelistMemberMapper memberMapper, CurrentUserProvider currentUserProvider) {
        this.userMapper = userMapper;
        this.activityMapper = activityMapper;
        this.sessionMapper = sessionMapper;
        this.configMapper = configMapper;
        this.memberMapper = memberMapper;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Checks the current authenticated user's eligibility inside the caller's business transaction.
     * Returns the trusted user ID for reservation/order ownership. Creation checks before INSERT;
     * payment locks the existing order first, then checks before quota, Zone and Seat writes.
     * Business state, time, capacity and purchase limits remain with callers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long check(Long activityId, Long sessionId) {
        return checkCurrentUser(activityId, sessionId);
    }

    /** Rechecks current eligibility in the payment transaction, using the order's stored scope IDs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long checkAgain(Long activityId, Long sessionId) {
        return checkCurrentUser(activityId, sessionId);
    }

    private Long checkCurrentUser(Long activityId, Long sessionId) {
        Long userId = currentUserProvider.getCurrentUserId();
        if (!isEligible(userId, activityId, sessionId)) {
            throw new QualificationDeniedException();
        }
        return userId;
    }

    /**
     * Checks account, scope and whitelist eligibility for an internal caller.
     * The caller obtains userId from CurrentUserProvider and validates the transaction's
     * activity and session states, time window, purchase limits and inventory separately.
     * Every business action checks again in its own transaction. FOR SHARE reads current
     * rows even when an enclosing transaction has an older snapshot. Its shared activity
     * lock coordinates with whitelist management's exclusive root lock until transaction end.
     * A previous order-creation result is never a payment authorization.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean canParticipate(Long userId, Long activityId, Long sessionId) {
        return isEligible(userId, activityId, sessionId);
    }

    private boolean isEligible(Long userId, Long activityId, Long sessionId) {
        if (!positive(userId) || !positive(activityId) || !positive(sessionId)) {
            return false;
        }
        AppUser user = userMapper.findByIdForShare(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return false;
        }
        if (activityMapper.findByIdForShare(activityId) == null) {
            return false;
        }
        ActivitySession session = sessionMapper.findByIdForShare(sessionId);
        if (session == null || !activityId.equals(session.getActivityId())) {
            return false;
        }

        WhitelistConfig sessionConfig = configMapper.findByScopeForShare(SESSION, sessionId);
        if (sessionConfig != null) {
            // A configured session completely overrides the activity, including an empty list.
            return memberMapper.findIdByConfigIdAndUserIdForShare(sessionConfig.getConfigId(), userId) != null;
        }
        WhitelistConfig activityConfig = configMapper.findByScopeForShare(ACTIVITY, activityId);
        if (activityConfig != null) {
            return memberMapper.findIdByConfigIdAndUserIdForShare(activityConfig.getConfigId(), userId) != null;
        }
        return true;
    }

    private boolean positive(Long id) {
        return id != null && id > 0;
    }
}
