package com.limitactivitysystem.reservation.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.reservation.dto.ReservationResponse;
import com.limitactivitysystem.reservation.exception.ReservationErrorCode;
import com.limitactivitysystem.reservation.exception.ReservationException;
import com.limitactivitysystem.reservation.mapper.FreeReservationMapper;
import com.limitactivitysystem.reservation.mapper.FreeSessionQuotaMapper;
import com.limitactivitysystem.reservation.model.FreeReservation;
import com.limitactivitysystem.reservation.model.FreeSessionQuota;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.whitelist.service.QualificationService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

import static com.limitactivitysystem.reservation.exception.ReservationErrorCode.*;

@Service
@Validated
public class FreeReservationService {
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final QualificationService qualification;
    private final FreeReservationMapper reservations;
    private final FreeSessionQuotaMapper quotas;

    public FreeReservationService(CurrentUserProvider currentUser, AppUserMapper users, ActivityMapper activities,
                                  ActivitySessionMapper sessions, QualificationService qualification,
                                  FreeReservationMapper reservations, FreeSessionQuotaMapper quotas) {
        this.currentUser = currentUser; this.users = users; this.activities = activities; this.sessions = sessions;
        this.qualification = qualification; this.reservations = reservations; this.quotas = quotas;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public ReservationResponse create(@NotNull @Positive Long sessionId) {
        Long userId = lockActiveUser();
        Scope preliminary = readScope(sessionId, false);
        validateCreation(preliminary, reservations.currentTime());
        if (!userId.equals(qualification.check(preliminary.activity().getActivityId(), sessionId))) {
            throw failure(RESERVATION_ACCESS_DENIED, "Qualification identity does not match current user");
        }
        Scope scope = readScope(sessionId, true);
        validateCreation(scope, reservations.currentTime());

        quotas.initializeForPublishedFreeSession(sessionId);
        validateQuota(quotas.findBySessionIdForUpdate(sessionId), scope.session());
        // Initialization/row-lock waits can cross the reservation deadline or session start.
        validateCreation(scope, reservations.currentTime());
        if (reservations.findActive(userId, sessionId) != null) {
            throw failure(RESERVATION_ALREADY_ACTIVE, "An active reservation already exists for this session");
        }
        if (quotas.takeOne(sessionId) != 1) { throw failure(RESERVATION_QUOTA_EXHAUSTED, "No reservation quota remains"); }

        var reservation = new FreeReservation(); reservation.setUserId(userId); reservation.setSessionId(sessionId);
        try {
            if (reservations.insertIfReservable(reservation) != 1) {
                validateCreation(scope, reservations.currentTime());
                throw failure(RESERVATION_CONFLICT, "Reservation could not be created");
            }
        } catch (DuplicateKeyException conflict) {
            if (!String.valueOf(conflict.getMostSpecificCause().getMessage()).contains("uq_free_reservation_active")) { throw conflict; }
            // Propagate out of the transaction proxy; the quota deduction must roll back too.
            throw failure(RESERVATION_ALREADY_ACTIVE, "An active reservation already exists for this session");
        }
        validateCreation(scope, reservations.currentTime());
        return response(reservations.findById(reservation.getReservationId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public ReservationResponse cancel(@NotNull @Positive Long reservationId) {
        Long userId = lockActiveUser();
        var preliminary = requireReservation(reservations.findById(reservationId));
        requireOwner(preliminary, userId);
        Scope scope = readScope(preliminary.getSessionId(), true);
        requireFree(scope);

        // Ordinary lookup above determines the scope; all writers lock quota before Reservation.
        validateQuota(quotas.findBySessionIdForUpdate(preliminary.getSessionId()), scope.session());
        var reservation = requireReservation(reservations.findByIdForUpdate(reservationId));
        requireOwner(reservation, userId);
        if (!reservation.getSessionId().equals(scope.session().getActivitySessionId())) {
            throw new IllegalStateException("Reservation scope changed");
        }
        if (!"ACTIVE".equals(reservation.getStatus())) { throw failure(RESERVATION_NOT_ACTIVE, "Reservation is not active"); }
        validateBeforeStart(scope.session(), reservations.currentTime());
        if (reservations.cancelIfActiveBeforeStart(reservationId, userId, reservation.getSessionId()) != 1) {
            validateBeforeStart(scope.session(), reservations.currentTime());
            throw failure(RESERVATION_CONFLICT, "Reservation could not be cancelled");
        }
        if (quotas.restoreOne(reservation.getSessionId()) != 1) {
            throw new IllegalStateException("Cancelled reservation quota could not be restored");
        }
        validateBeforeStart(scope.session(), reservations.currentTime());
        return response(reservations.findById(reservationId));
    }

    private Long lockActiveUser() {
        Long userId = currentUser.getAuthenticatedUserId();
        var user = users.findByIdForShare(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) { throw failure(USER_DISABLED, "Current account is not active"); }
        return userId;
    }

    private Scope readScope(Long sessionId, boolean locked) {
        var session = sessions.findById(sessionId);
        if (session == null) { throw failure(SESSION_NOT_FOUND, "Session not found"); }
        var activity = locked ? activities.findByIdForShare(session.getActivityId()) : activities.findById(session.getActivityId());
        if (activity == null) { throw failure(ACTIVITY_NOT_FOUND, "Activity not found"); }
        if (locked) { session = sessions.findByIdForShare(sessionId); }
        if (session == null || !activity.getActivityId().equals(session.getActivityId())) {
            throw failure(SESSION_NOT_AVAILABLE, "Session scope is unavailable");
        }
        return new Scope(activity, session);
    }

    private void validateCreation(Scope scope, LocalDateTime now) {
        requireFree(scope);
        if (!"PUBLISHED".equals(scope.activity().getStatus())) { throw failure(ACTIVITY_NOT_PUBLISHED, "Activity is not published"); }
        if (!"SCHEDULED".equals(scope.session().getStatus())) { throw failure(SESSION_NOT_AVAILABLE, "Session is unavailable"); }
        validateBeforeStart(scope.session(), now);
        if (scope.session().getCapacity() == null || scope.session().getCapacity() <= 0
                || scope.session().getSeatMode() != null || scope.session().getMaxTicketsPerUser() != null) {
            throw failure(INVALID_SESSION_CONFIG, "Invalid free session configuration");
        }
        var activity = scope.activity();
        if (activity.getOpenTime() == null || activity.getCloseTime() == null || !activity.getOpenTime().isBefore(activity.getCloseTime())) {
            throw failure(INVALID_SESSION_CONFIG, "Invalid reservation window");
        }
        if (now.isBefore(activity.getOpenTime())) { throw failure(RESERVATION_WINDOW_NOT_OPEN, "Reservation window is not open"); }
        if (!now.isBefore(activity.getCloseTime())) { throw failure(RESERVATION_WINDOW_CLOSED, "Reservation window has closed"); }
    }

    private void requireFree(Scope scope) {
        if (!"FREE".equals(scope.activity().getType())) { throw failure(ACTIVITY_NOT_FREE, "Only FREE activities accept reservations"); }
    }
    private void validateBeforeStart(ActivitySession session, LocalDateTime now) {
        if (session.getStartTime() == null || !now.isBefore(session.getStartTime())) { throw failure(SESSION_STARTED, "Session has already started"); }
    }
    private void validateQuota(FreeSessionQuota quota, ActivitySession session) {
        if (quota == null || !quota.getCapacity().equals(session.getCapacity())) {
            throw new IllegalStateException("Reservation quota is missing or differs from session capacity");
        }
    }
    private FreeReservation requireReservation(FreeReservation reservation) {
        if (reservation == null) { throw failure(RESERVATION_NOT_FOUND, "Reservation not found"); }
        return reservation;
    }
    private void requireOwner(FreeReservation reservation, Long userId) {
        if (!userId.equals(reservation.getUserId())) { throw failure(RESERVATION_ACCESS_DENIED, "Only the owner can cancel this reservation"); }
    }
    private ReservationResponse response(FreeReservation row) {
        return new ReservationResponse(row.getReservationId(), row.getUserId(), row.getSessionId(), row.getStatus(), row.getCreatedAt(), row.getCancelledAt());
    }
    private ReservationException failure(ReservationErrorCode code, String message) { return new ReservationException(code, message); }
    private record Scope(Activity activity, ActivitySession session) { }
}
