package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.activity.service.ActivityOwnershipService;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ActivityOwnershipMySqlIntegrationTest extends ActivityMySqlTestSupport {

    @Autowired private ActivityOwnershipService ownershipService;

    private Activity activity;
    private ActivitySession session;
    private SessionZone zone;
    private SessionSeat seat;

    @BeforeEach
    void insertResourceChain() {
        activity = insertDraft(publisher, "PAID");
        session = insertSession(activity);
        zone = insertZone(session, "PAID", 1, 1);
        seat = seatMapper.findByZoneId(zone.getZoneId()).get(0);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void resolvesTheStoredChainForTheCurrentOwner(Resource resource) throws Exception {
        Object actual = asPublisher(() -> resolve(resource));
        Object expected = switch (resource) {
            case ACTIVITY -> activity;
            case SESSION -> new ActivityOwnershipService.SessionOwnership(activity, session);
            case ZONE -> new ActivityOwnershipService.ZoneOwnership(activity, session, zone);
            case SEAT -> new ActivityOwnershipService.SeatOwnership(activity, session, zone, seat);
        };
        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
        if (resource == Resource.SEAT) {
            SessionSeat storedSeat = ((ActivityOwnershipService.SeatOwnership) actual).seat();
            assertThat(storedSeat.getSeatId()).isEqualTo(seat.getSeatId());
            assertThat(storedSeat.getZoneId()).isEqualTo(zone.getZoneId());
            assertThat(storedSeat.getRowNo()).isEqualTo(1);
            assertThat(storedSeat.getSeatNo()).isEqualTo(1);
            assertThat(storedSeat.getAllocationPriority()).isEqualTo(1);
            assertThat(storedSeat.getAllocationStatus()).isEqualTo("AVAILABLE");
        }
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void rejectsResourcesOwnedByAnotherPublisher(Resource resource) {
        jdbc.update("UPDATE activity SET publisher_id = ? WHERE activity_id = ?",
                otherPublisher.getId(), activity.getActivityId());
        assertThatThrownBy(() -> asPublisher(() -> resolve(resource)))
                .isInstanceOfSatisfying(ActivityException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ActivityErrorCode.NOT_ACTIVITY_OWNER));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void ownershipCheckAlsoWorksAfterPublication(Resource resource) throws Exception {
        // Ownership alone does not freeze whitelist maintenance. Mutation services enforce DRAFT.
        assertThat(activityMapper.updateStatus(activity.getActivityId(), "PUBLISHED")).isEqualTo(1);
        assertThat(asPublisher(() -> resolve(resource))).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void rereadsTheDatabaseRoleInsteadOfTrustingThePreviouslyAuthenticatedPublisherRole(Resource resource) {
        assertThatThrownBy(() -> asPublisher(() -> {
            jdbc.update("UPDATE app_user SET role = 'USER' WHERE id = ?", publisher.getId());
            return resolve(resource);
        })).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void rejectsAnAccountDisabledAfterItsTokenWasAuthenticated(Resource resource) {
        assertThatThrownBy(() -> asPublisher(() -> {
            jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", publisher.getId());
            return resolve(resource);
        })).isInstanceOf(InvalidBearerTokenException.class);
    }

    @ParameterizedTest(name = "{0}: missing {1}")
    @CsvSource({
            "ACTIVITY, ACTIVITY, ACTIVITY_NOT_FOUND",
            "SESSION, SESSION, SESSION_NOT_FOUND",
            "SESSION, ACTIVITY, ACTIVITY_NOT_FOUND",
            "ZONE, ZONE, ZONE_NOT_FOUND",
            "ZONE, SESSION, SESSION_NOT_FOUND",
            "ZONE, ACTIVITY, ACTIVITY_NOT_FOUND",
            "SEAT, SEAT, SEAT_NOT_FOUND",
            "SEAT, ZONE, ZONE_NOT_FOUND",
            "SEAT, SESSION, SESSION_NOT_FOUND",
            "SEAT, ACTIVITY, ACTIVITY_NOT_FOUND"
    })
    void rejectsMissingResourcesAnywhereInTheChain(Resource resource, Resource missing,
                                                  ActivityErrorCode expected) {
        // There are no physical foreign keys. Deleting a parent really leaves an orphan here.
        // The failed ownership check rolls the deletion back, preserving fixtures for cleanup.
        assertThatThrownBy(() -> asPublisher(() ->
                new TransactionTemplate(transactionManager).execute(transaction -> {
                    deleteResource(missing);
                    return resolve(resource);
                })))
                .isInstanceOfSatisfying(ActivityException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(expected);
                    assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(404);
                });
        assertThat(activityMapper.findById(activity.getActivityId())).isNotNull();
        assertThat(sessionMapper.findById(session.getActivitySessionId())).isNotNull();
        assertThat(zoneMapper.findById(zone.getZoneId())).isNotNull();
        assertThat(seatMapper.findById(seat.getSeatId())).isNotNull();
    }

    private Object resolve(Resource resource) {
        return switch (resource) {
            case ACTIVITY -> ownershipService.assertActivityOwner(activity.getActivityId());
            case SESSION -> ownershipService.assertSessionOwner(session.getActivitySessionId());
            case ZONE -> ownershipService.assertZoneOwner(zone.getZoneId());
            case SEAT -> ownershipService.assertSeatOwner(seat.getSeatId());
        };
    }

    private void deleteResource(Resource resource) {
        int deleted = switch (resource) {
            case ACTIVITY -> jdbc.update("DELETE FROM activity WHERE activity_id = ?", activity.getActivityId());
            case SESSION -> jdbc.update("DELETE FROM activity_session WHERE activity_session_id = ?",
                    session.getActivitySessionId());
            case ZONE -> jdbc.update("DELETE FROM session_zone WHERE zone_id = ?", zone.getZoneId());
            case SEAT -> jdbc.update("DELETE FROM session_seat WHERE seat_id = ?", seat.getSeatId());
        };
        assertThat(deleted).isEqualTo(1);
    }

    enum Resource {
        ACTIVITY, SESSION, ZONE, SEAT
    }
}
