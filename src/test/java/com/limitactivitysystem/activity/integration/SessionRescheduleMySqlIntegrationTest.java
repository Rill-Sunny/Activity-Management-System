package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.messaging.*;
import com.limitactivitysystem.outbox.*;
import com.limitactivitysystem.reschedule.*;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.support.*;
import com.limitactivitysystem.ticketorder.mapper.*;
import com.limitactivitysystem.ticketorder.model.Ticket;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={JwtTestTokens.SECRET_PROPERTY,"spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import({CurrentSchemaTestConfiguration.class,IsolatedRabbitTestConfiguration.class,SessionRescheduleMySqlIntegrationTest.ObservationConfiguration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@RequiresEnvironmentVariable(named="RUN_DAY22_TESTS",matches="true")
class SessionRescheduleMySqlIntegrationTest extends PaymentResourceTestSupport {
    static final String PREFIX="it.reschedule."+UUID.randomUUID().toString().replace("-","")+".";
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static final Map<String,Object> AUDIT=new ConcurrentSkipListMap<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) { values.add("messaging.rabbit.name-prefix",()->PREFIX); }
    @MockitoBean(name="rescheduleClock") Clock clock;
    @MockitoBean(name="ticketRefundClock") Clock ordinaryClock;
    @Autowired TicketMapper ticketMapper;
    @Autowired RescheduleMapper events;
    @Autowired RescheduleRetentionService retention;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RescheduleAsyncHandler handlers;
    @Autowired IsolatedRabbitTestConfiguration rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired OutboxClaimService claims;
    @Autowired OutboxPublisher publisherRelay;
    @Autowired OutboxRelayService relay;
    @Autowired OutboxTraceSupport traceSupport;
    @Autowired DatabaseTimeMapper databaseTime;
    @Autowired Observation observation;
    LocalDateTime fixedNow;
    LocalDateTime baseTime;
    Activity activity;
    ActivitySession session;
    SessionZone zone;
    long orderId;
    List<Ticket> sold;

    @BeforeEach void setupJourney(TestInfo info) throws Exception {
        fixedNow=null;
        when(clock.getZone()).thenReturn(ZONE);
        when(clock.instant()).thenAnswer(call->(fixedNow==null?databaseTime.currentTime():fixedNow).atZone(ZONE).toInstant());
        when(ordinaryClock.getZone()).thenReturn(ZONE);
        when(ordinaryClock.instant()).thenAnswer(call->(fixedNow==null?databaseTime.currentTime():fixedNow).atZone(ZONE).toInstant());
        baseTime=databaseTime.currentTime();
        activity=insertDraft(publisher,"PAID");
        boolean shortWindow=Set.of("t07T08ConfirmedTicketCanRefundAndAcceptedPolicySurvivesDelayedExecution",
                "at18T11AutoRetentionIsDistinctAndNeverReactivatesRefundedTickets",
                "scannerRecoversPendingResponseWithoutMqAndSkipsAcceptedRefund","realRabbitWindowMessageRetainsOnlyOnce")
                .contains(info.getTestMethod().orElseThrow().getName());
        jdbc.update("UPDATE activity SET open_time=?,close_time=? WHERE activity_id=?",baseTime.minusDays(10),
                shortWindow?baseTime.plusSeconds(8):baseTime.plusDays(1),activity.getActivityId());
        session=insertSession(activity);
        jdbc.update("UPDATE activity_session SET start_time=?,end_time=?,max_tickets_per_user=10 WHERE activity_session_id=?",
                baseTime.plusDays(20),baseTime.plusDays(20).plusHours(2),session.getActivitySessionId());
        zone=insertZone(session,"PAID",10,10);
        jdbc.update("UPDATE session_zone SET price=123.45 WHERE zone_id=?",zone.getZoneId());
        publish(activity,publisher).andExpect(status().isNoContent());
        orderId=newOrder(0,1); pay(orderId).andExpect(status().isOk());
        sold=ticketMapper.findByOrderId(orderId); assertThat(sold).hasSize(2);
    }

    @AfterEach void reconcileAndClean(TestInfo info) throws Exception {
        observation.current.remove();
        rabbit.cleanup(info.getTestMethod().orElseThrow().getName());
        try {
            var snapshot=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            snapshot.setReadOnly(true); snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var differences=snapshot.execute(transaction->{
                var rows=new ArrayList<Map<String,Object>>();
                try(var scripts=Files.list(Path.of("tests/sql"))) {
                    for(var script:scripts.filter(path->path.toString().endsWith(".sql")).sorted().toList()) {
                        String sql=Files.readString(script).replaceAll("(?m)--[^\\r\\n]*","");
                        for(String statement:sql.split(";")) if(!statement.isBlank()) {
                            var result=jdbc.queryForList(statement);
                            if(!script.getFileName().toString().equals("messaging_reconciliation.sql")) rows.addAll(result);
                        }
                    }
                } catch(java.io.IOException failure) {throw new IllegalStateException(failure);}
                return rows;
            });
            AUDIT.put(info.getDisplayName(),Map.of("differences",differences,"events",jdbc.queryForList("SELECT * FROM session_reschedule_event"),
                    "responses",jdbc.queryForList("SELECT * FROM reschedule_response"),"refunds",jdbc.queryForList("SELECT refund_id,status,refund_policy,source_event_id,accepted_at FROM ticket_refund_request")));
            assertThat(differences).as(info.getDisplayName()).isEmpty();
        } finally {
            for(String table:List.of("reschedule_refund_link","reschedule_refund_submission","reschedule_response","reschedule_affected_ticket",
                    "session_reschedule_event","ticket_refund_item","ticket_refund_request","notification","consumer_failure","consumer_process","outbox_event")) jdbc.update("DELETE FROM "+table);
        }
    }
    @AfterAll static void saveAudit() throws Exception {
        var file=Path.of("target/day22/reconciliation.json"); Files.createDirectories(file.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),AUDIT);
    }

    @Test void at16DedicatedApiPreservesTicketsSeatsAndFrozenConfiguration() throws Exception {
        var before=jdbc.queryForList("SELECT * FROM ticket ORDER BY ticket_id");
        var seats=jdbc.queryForList("SELECT * FROM session_seat ORDER BY seat_id");
        var activityBefore=jdbc.queryForMap("SELECT * FROM activity WHERE activity_id=?",activity.getActivityId());
        mvc.perform(patch("/publisher/sessions/{id}",session.getActivitySessionId()).header(HttpHeaders.AUTHORIZATION,bearer(publisher))
                .contentType(MediaType.APPLICATION_JSON).content("{\"startTime\":\"2099-12-01T00:00:00\"}"))
                .andExpect(result->assertThat(result.getResponse().getStatus()).isIn(404,405));
        long event=createEvent(30);
        assertThat(jdbc.queryForList("SELECT * FROM ticket ORDER BY ticket_id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM session_seat ORDER BY seat_id")).isEqualTo(seats);
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id=?",activity.getActivityId())).isEqualTo(activityBefore);
        assertThat(events.event(event).getOldStartTime()).isEqualTo(baseTime.plusDays(20));
        assertThat(count("SELECT COUNT(*) FROM reschedule_affected_ticket WHERE event_id=?",event)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(ints={-2,0})
    void t01InvalidStartEndRejected(int hours) throws Exception {
        var body=body(30); body.put("newEndTime",offset(baseTime.plusDays(30).plusHours(hours)));
        reschedule(body,publisher).andExpect(status().isBadRequest()); assertThat(count("SELECT COUNT(*) FROM session_reschedule_event")).isZero();
    }
    @Test void actWindowAndMissingTimeZoneCannotBeBypassed() throws Exception {
        reschedule(body(0),publisher).andExpect(status().isBadRequest());
        var input=body(30); input.put("newStartTime",baseTime.plusDays(30).toString());
        reschedule(input,publisher).andExpect(status().isBadRequest());
        input=body(30); input.put("operatorId",otherPublisher.getId());
        reschedule(input,publisher).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings={"CANCELLED","FINISHED"})
    void unavailableLifecycleCannotBeRescheduled(String state) throws Exception {
        jdbc.update("UPDATE activity_session SET status=? WHERE activity_session_id=?",state,session.getActivitySessionId());
        reschedule(body(30),publisher).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM session_reschedule_event")).isZero();
    }
    @ParameterizedTest @ValueSource(ints={2,30})
    void at17DatabaseAnnouncementUsesTheEarlierDeadline(int days) throws Exception {
        long eventId=createEvent(days); var event=events.event(eventId);
        var expected=event.getAnnouncementTime().plusDays(7);
        if(event.getNewStartTime().isBefore(expected)) expected=event.getNewStartTime();
        assertThat(event.getSpecialRefundDeadline()).isEqualTo(expected);
        assertThat(event.getAnnouncementTime()).isBetween(baseTime,databaseTime.currentTime());
    }
    @Test void t03T04SnapshotSeparatesPaymentsBeforeAndAfterAnnouncement() throws Exception {
        long event=createEvent(30); long later=newOrder(2); pay(later).andExpect(status().isOk());
        long laterTicket=ticketMapper.findByOrderId(later).getFirst().getTicketId();
        special(event,List.of(laterTicket),UUID.randomUUID().toString(),buyer).andExpect(status().isForbidden());
        special(event,List.of(sold.getFirst().getTicketId()),UUID.randomUUID().toString(),buyer).andExpect(status().isCreated());
        assertThat(count("SELECT COUNT(*) FROM reschedule_affected_ticket WHERE event_id=?",event)).isEqualTo(2);
    }
    @ParameterizedTest @CsvSource({"-1,201","0,409","1,409"})
    void t05T06ExclusiveDeadlineAcceptance(long milliseconds,int expected) throws Exception {
        long event=createEvent(30); fixedNow=events.event(event).getSpecialRefundDeadline().plusNanos(milliseconds*1_000_000);
        special(event,ids(),UUID.randomUUID().toString(),buyer).andExpect(status().is(expected));
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request")).isEqualTo(expected==201?1:0);
    }
    @Test void t07T08ConfirmedTicketCanRefundAndAcceptedPolicySurvivesDelayedExecution() throws Exception {
        long event=shortEvent();
        confirm(event,ids(),buyer).andExpect(status().isOk());
        var confirmed=jdbc.queryForList("SELECT * FROM reschedule_response ORDER BY ticket_id");
        long refund=specialId(event,ids(),"accepted-before-close");
        awaitWindow(event);
        process(refund).andExpect(status().isOk()); process(refund).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT processed_at FROM ticket_refund_request WHERE refund_id=?",LocalDateTime.class,refund))
                .isAfterOrEqualTo(events.event(event).getSpecialRefundDeadline());
        assertThat(jdbc.queryForList("SELECT * FROM reschedule_response ORDER BY ticket_id")).isEqualTo(confirmed);
        assertThat(jdbc.queryForObject("SELECT SUM(refund_amount) FROM ticket_refund_item WHERE refund_id=?",java.math.BigDecimal.class,refund)).isEqualByComparingTo("246.90");
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_item WHERE fee_rate<>0")).isZero();
        assertThat(ticketMapper.findByOrderId(orderId)).allMatch(t->t.getStatus().equals("REFUNDED"));
    }
    @Test void t09ConfirmationAndRefundReplayKeepOriginalFacts() throws Exception {
        long event=createEvent(30); confirm(event,ids(),buyer).andExpect(status().isOk());
        var before=jdbc.queryForList("SELECT * FROM reschedule_response ORDER BY ticket_id");
        confirm(event,ids(),buyer).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT * FROM reschedule_response ORDER BY ticket_id")).isEqualTo(before);
        long refund=specialId(event,ids(),"same"); process(refund).andExpect(status().isOk());
        assertThat(specialId(event,ids(),"same")).isEqualTo(refund);
        special(event,List.of(ids().getFirst()),"same",buyer).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request")).isEqualTo(1);
    }
    @Test void t10EachRescheduleHasIndependentHistoryResponsesAndEligibility() throws Exception {
        long first=createEvent(30); confirm(first,ids(),buyer).andExpect(status().isOk());
        long second=createEvent(40);
        assertThat(events.event(second).getOldStartTime()).isEqualTo(events.event(first).getNewStartTime());
        assertThat(jdbc.queryForList("SELECT status FROM reschedule_response WHERE event_id=?",String.class,first)).containsOnly("USER_CONFIRMED");
        assertThat(jdbc.queryForList("SELECT status FROM reschedule_response WHERE event_id=?",String.class,second)).containsOnly("PENDING");
        long refund=specialId(first,List.of(ids().getFirst()),"old-event-still-valid"); process(refund).andExpect(status().isOk());
        special(second,List.of(ids().getFirst()),"second-claim",buyer).andExpect(status().isConflict());
        long third=createEvent(50);
        assertThat(count("SELECT COUNT(*) FROM reschedule_affected_ticket WHERE event_id=?",third)).isEqualTo(1);
        mvc.perform(get("/reschedules").header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }
    @Test void at18T11AutoRetentionIsDistinctAndNeverReactivatesRefundedTickets() throws Exception {
        long event=shortEvent(); long refund=specialId(event,List.of(ids().getFirst()),"refund-one"); process(refund).andExpect(status().isOk());
        awaitWindow(event);
        String message=jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_type='RESCHEDULE_WINDOW_CLOSED'",String.class);
        handlers.retain(message,event); handlers.retain(message,event);
        retention.retain(event,ids().get(1));
        assertThat(jdbc.queryForObject("SELECT status FROM reschedule_response WHERE event_id=? AND ticket_id=?",String.class,event,ids().getFirst())).isEqualTo("PENDING");
        var row=jdbc.queryForMap("SELECT * FROM reschedule_response WHERE event_id=? AND ticket_id=?",event,ids().get(1));
        assertThat(row.get("status")).isEqualTo("AUTO_RETAINED"); assertThat(row.get("responded_at")).isNull();
        assertThat(row.get("auto_retained_at")).isNotNull(); assertThat(row.get("source")).isEqualTo("MQ");
        assertThat(count("SELECT COUNT(*) FROM consumer_process WHERE event_id=?",message)).isEqualTo(1);
        assertThat(ticketMapper.findByOrderId(orderId).getFirst().getStatus()).isEqualTo("REFUNDED");
    }
    @Test void scannerRecoversPendingResponseWithoutMqAndSkipsAcceptedRefund() throws Exception {
        long event=shortEvent(); specialId(event,List.of(ids().getFirst()),"accepted-pending");
        awaitWindow(event);
        new RescheduleRetentionJob(events,retention).scan();
        assertThat(count("SELECT COUNT(*) FROM reschedule_response WHERE status='AUTO_RETAINED' AND source='DEADLINE_SCAN'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reschedule_response WHERE status='PENDING'")).isEqualTo(1);
    }
    @Test void t12OrdinaryAndSpecialRefundCompeteForOneTicket() throws Exception {
        long event=createEvent(40); var barrier=new CyclicBarrier(2);
        var ordinary=async(()->{barrier.await(10,TimeUnit.SECONDS);return ordinary(ids()).andReturn().getResponse().getStatus();});
        var special=async(()->{barrier.await(10,TimeUnit.SECONDS);return special(event,ids(),"competing",buyer).andReturn().getResponse().getStatus();});
        assertThat(List.of(ordinary.get(20,TimeUnit.SECONDS),special.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request")).isEqualTo(1);
        long refund=jdbc.queryForObject("SELECT refund_id FROM ticket_refund_request",Long.class); process(refund).andExpect(status().isOk());
    }
    @Test void t13ExpiredSpecialWindowDoesNotForbidLegalOrdinaryRefund() throws Exception {
        long event=createEvent(50); fixedNow=events.event(event).getSpecialRefundDeadline().plusDays(1);
        special(event,ids(),"too-late",buyer).andExpect(status().isConflict());
        ordinary(ids()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT refund_policy FROM ticket_refund_request",String.class)).isEqualTo("NORMAL_REFUND");
    }
    @Test void t14OwnershipRoleAndCrossUserRequestsAreRejected() throws Exception {
        reschedule(body(30),buyer).andExpect(status().isForbidden());
        reschedule(body(30),otherPublisher).andExpect(status().isForbidden());
        long event=createEvent(30);
        special(event,ids(),"intruder",otherPublisher).andExpect(status().isForbidden());
        confirm(event,ids(),otherPublisher).andExpect(status().isForbidden());
        mvc.perform(get("/reschedules/{id}",event).header(HttpHeaders.AUTHORIZATION,bearer(otherPublisher))).andExpect(status().isForbidden());
        mvc.perform(get("/reschedules/{id}",event)).andExpect(status().isUnauthorized());
    }
    @Test void multiOrderSpecialRefundIsAtomicAndUsesOriginalPrices() throws Exception {
        long other=newOrder(2); pay(other).andExpect(status().isOk()); long otherTicket=ticketMapper.findByOrderId(other).getFirst().getTicketId();
        long event=createEvent(40);
        var response=special(event,List.of(ids().getFirst(),otherTicket),"multi",buyer).andExpect(status().isCreated()).andReturn().getResponse();
        var json=objectMapper.readTree(response.getContentAsString()); assertThat(json.path("refunds").size()).isEqualTo(2);
        for(var refund:json.path("refunds")) process(refund.path("refundId").asLong()).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_item WHERE status='SUCCEEDED'")).isEqualTo(2);
    }
    @Test void secondOrderConflictRejectsTheWholeSpecialRefundSubmission() throws Exception {
        long other=newOrder(2); pay(other).andExpect(status().isOk()); long otherTicket=ticketMapper.findByOrderId(other).getFirst().getTicketId();
        long event=createEvent(40); specialId(event,List.of(otherTicket),"already-claimed");
        special(event,List.of(ids().getFirst(),otherTicket),"must-not-partially-accept",buyer).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM reschedule_refund_submission")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_item WHERE ticket_id=?",ids().getFirst())).isZero();
    }
    @Test void publisherRequestReplayAndConflictDoNotCreateExtraEvents() throws Exception {
        var input=body(30); var first=reschedule(input,publisher).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(reschedule(input,publisher).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        input.put("reason","different"); reschedule(input,publisher).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM session_reschedule_event")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"USER_CONFIRMED","AUTO_RETAINED"})
    void schemaRejectsMissingResponseSource(String state) throws Exception {
        long event=createEvent(30);
        String column=state.equals("USER_CONFIRMED")?"responded_at":"auto_retained_at";
        assertThatThrownBy(()->jdbc.update("UPDATE reschedule_response SET status=?,"+column+"=CURRENT_TIMESTAMP(3),source=NULL WHERE event_id=?",state,event))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("chk_reschedule_response");
        assertThat(count("SELECT COUNT(*) FROM reschedule_response WHERE status='PENDING'")).isEqualTo(2);
    }
    @Test void insertedEventAndSnapshotRollBackWhenSessionUpdateFails() throws Exception {
        var before=jdbc.queryForMap("SELECT * FROM activity_session WHERE activity_session_id=?",session.getActivitySessionId());
        var probe=new Probe("ActivitySessionMapper.reschedule",true,false); probe.fail=true; observation.current.set(probe);
        try { reschedule(body(30),publisher).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RESCHEDULE_DATABASE_FAILURE")); }
        finally { observation.current.remove(); }
        assertThat(probe.rowsBeforeFailure).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM session_reschedule_event")).isZero();
        assertThat(count("SELECT COUNT(*) FROM reschedule_affected_ticket")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='SESSION_RESCHEDULED'")).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM activity_session WHERE activity_session_id=?",session.getActivitySessionId())).isEqualTo(before);
        save("rollback",Map.of("eventInsideTransaction",probe.rowsBeforeFailure,"eventAfterRollback",0,"session",before));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void announcementAndPaymentHaveARealDatabaseCutoff(boolean paymentFirst) throws Exception {
        long later=newOrder(2); var holder=new Probe(paymentFirst?"ActivitySessionMapper.findByIdForShare":"RescheduleMapper.initializeResponses",false,true);
        var waiter=new Probe(paymentFirst?"ActivitySessionMapper.findByIdForUpdate":"ActivitySessionMapper.findByIdForShare",true,false);
        var first=async(()->observed(holder,()-> paymentFirst?pay(later).andExpect(status().isOk()).andReturn().getResponse().getStatus():createEvent(30)));
        await(holder.entered);
        var second=async(()->observed(waiter,()-> paymentFirst?createEvent(30):pay(later).andExpect(status().isOk()).andReturn().getResponse().getStatus()));
        try { await(waiter.entered); save("cutoff-"+paymentFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity_session")); }
        finally { holder.release.countDown(); }
        long firstResult=first.get(20,TimeUnit.SECONDS); long secondResult=second.get(20,TimeUnit.SECONDS);
        long event=paymentFirst?secondResult:firstResult;
        assertThat(count("SELECT COUNT(*) FROM reschedule_affected_ticket WHERE event_id=?",event)).isEqualTo(paymentFirst?3:2);
        assertThat(ticketMapper.findByOrderId(later)).hasSize(1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void announcementAndRefundSerializeWithoutReallocatingSeats(boolean refundFirst) throws Exception {
        long refund=objectMapper.readTree(ordinary(List.of(ids().getFirst())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("refundId").asLong();
        var holder=new Probe(refundFirst?"ActivitySessionMapper.findByIdForShare":"RescheduleMapper.initializeResponses",false,true);
        var waiter=new Probe(refundFirst?"ActivitySessionMapper.findByIdForUpdate":"ActivitySessionMapper.findByIdForShare",true,false);
        var first=async(()->observed(holder,()->refundFirst?process(refund).andExpect(status().isOk()).andReturn().getResponse().getStatus():createEvent(30)));
        await(holder.entered);
        var second=async(()->observed(waiter,()->refundFirst?createEvent(30):process(refund).andExpect(status().isOk()).andReturn().getResponse().getStatus()));
        try { await(waiter.entered); save("refund-cutoff-"+refundFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity_session")); }
        finally { holder.release.countDown(); }
        long a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS); long event=refundFirst?b:a;
        assertThat(count("SELECT COUNT(*) FROM reschedule_affected_ticket WHERE event_id=?",event)).isEqualTo(refundFirst?1:2);
        assertThat(ticketMapper.findByOrderId(orderId).get(1).getSeatId()).isEqualTo(sold.get(1).getSeatId());
    }
    @Test void cancellationLifecycleGateWinsBeforeRescheduleWithoutLockingOrders() throws Exception {
        var locked=gate(); var release=gate(); var connection=new AtomicLong();
        var cancel=async(()->tx(()->{
            activityMapper.findByIdForUpdate(activity.getActivityId()); connection.set(connectionId()); locked.countDown(); await(release);
            jdbc.update("UPDATE activity SET status='CANCELLED' WHERE activity_id=?",activity.getActivityId()); return true;
        }));
        await(locked); var probe=new Probe("ActivityMapper.findByIdForShare",true,false);
        var attempt=async(()->observed(probe,()->reschedule(body(30),publisher).andReturn().getResponse().getStatus()));
        try { await(probe.entered); save("cancel-gate",awaitDatabaseLockWait(probe.connection,connection.get(),"activity")); }
        finally { release.countDown(); }
        assertThat(cancel.get(20,TimeUnit.SECONDS)).isTrue(); assertThat(attempt.get(20,TimeUnit.SECONDS)).isEqualTo(409L);
        assertThat(count("SELECT COUNT(*) FROM session_reschedule_event")).isZero();
    }
    @Test void committedRescheduleSurvivesMqConnectionFailureAndRealListenerRecovers() throws Exception {
        rabbit.prepare(); long event=createEvent(30);
        var unavailable=new CachingConnectionFactory("127.0.0.1",unusedPort()); unavailable.setConnectionTimeout(500);
        unavailable.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); unavailable.setPublisherReturns(true);
        try {
            var template=new RabbitTemplate(unavailable); template.setMandatory(true);
            var sender=new OutboxPublisher(template,objectMapper,databaseTime,traceSupport,1000,PREFIX);
            new OutboxRelayService(claims,sender,50,8,60).publishClaimedEvents();
            assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type IN ('SESSION_RESCHEDULED','RESCHEDULE_WINDOW_CLOSED') AND status='PENDING' AND attempts=1")).isEqualTo(2);
            assertThat(events.event(event)).isNotNull(); save("mq-unavailable",jdbc.queryForList("SELECT event_id,event_type,status,attempts,failure_reason FROM outbox_event"));
        } finally { unavailable.destroy(); }
        jdbc.update("UPDATE outbox_event SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE status='PENDING'");
        listeners.start(); relay.publishClaimedEvents();
        awaitCount("SELECT COUNT(*) FROM notification WHERE content LIKE '场次改期%'",1);
        assertThat(count("SELECT COUNT(*) FROM consumer_process WHERE consumer_name=?",RescheduleAsyncHandler.NOTICE)).isEqualTo(1);
        save("mq-recovered",jdbc.queryForList("SELECT event_id,event_type,status,attempts FROM outbox_event"));
        String messageId=jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_type='SESSION_RESCHEDULED'",String.class);
        handlers.notify(messageId,event);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE content LIKE '场次改期%'")).isEqualTo(1);
    }
    @Test void realRabbitWindowMessageRetainsOnlyOnce() throws Exception {
        rabbit.prepare(); long event=shortEvent();
        listeners.start(); relay.publishClaimedEvents();
        awaitCount("SELECT COUNT(*) FROM reschedule_response WHERE status='AUTO_RETAINED'",2);
        var original=jdbc.queryForList("SELECT * FROM reschedule_response ORDER BY ticket_id");
        String messageId=jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_type='RESCHEDULE_WINDOW_CLOSED'",String.class);
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?",String.class,messageId);
        var properties=new org.springframework.amqp.core.MessageProperties();
        properties.setHeader("x-event-id",messageId); properties.setMessageId(messageId);
        properties.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(PREFIX+RabbitMqTopology.EVENTS_EXCHANGE,"session.reschedule.retain",
                new org.springframework.amqp.core.Message(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),properties));
        verify(handlers,timeout(5_000).times(2)).retain(messageId,event);
        assertThat(jdbc.queryForList("SELECT * FROM reschedule_response ORDER BY ticket_id")).isEqualTo(original);
        assertThat(count("SELECT COUNT(*) FROM reschedule_response WHERE responded_at IS NOT NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM consumer_process WHERE consumer_name=?",RescheduleAsyncHandler.RETENTION)).isEqualTo(1);
    }

    Map<String,Object> body(int days) { return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"newStartTime",offset(baseTime.plusDays(days)),"newEndTime",offset(baseTime.plusDays(days).plusHours(2)),"reason","场馆档期调整")); }
    String offset(LocalDateTime time) { return time.atZone(ZONE).toOffsetDateTime().toString(); }
    ResultActions reschedule(Map<String,Object> input,AppUser user) throws Exception { return postJson("/publisher/sessions/"+session.getActivitySessionId()+"/reschedules",user,input); }
    long createEvent(int days) throws Exception { return objectMapper.readTree(reschedule(body(days),publisher).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("eventId").asLong(); }
    List<Long> ids() { return sold.stream().map(Ticket::getTicketId).toList(); }
    ResultActions confirm(long event,List<Long> ids,AppUser user) throws Exception { return postJson("/reschedules/"+event+"/confirm",user,Map.of("ticketIds",ids)); }
    ResultActions special(long event,List<Long> ids,String key,AppUser user) throws Exception { return postJson("/reschedules/"+event+"/special-refunds",user,Map.of("ticketIds",ids,"requestId",key)); }
    long specialId(long event,List<Long> ids,String key) throws Exception { return objectMapper.readTree(special(event,ids,key,buyer).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("refunds").get(0).path("refundId").asLong(); }
    ResultActions ordinary(List<Long> ids) throws Exception { return postJson("/ticket-refunds",buyer,Map.of("requestId",UUID.randomUUID().toString(),"orderId",orderId,"ticketIds",ids,"reason","ordinary")); }
    ResultActions process(long refund) throws Exception { return mvc.perform(post("/ticket-refunds/{id}/process",refund).header(HttpHeaders.AUTHORIZATION,bearer(buyer))); }
    long newOrder(int... positions) throws Exception {
        var seats=seatMapper.findByZoneId(zone.getZoneId()); var items=new ArrayList<Map<String,Object>>();
        for(int position:positions) items.add(Map.of("zoneId",zone.getZoneId(),"allocationMode","SELECT_SEAT",
                "requestedSeatId",seats.get(position).getSeatId(),"quantity",1,"acceptNonAdjacent",false));
        var response=postJson("/ticket-orders",buyer,Map.of("requestId",UUID.randomUUID().toString(),"sessionId",session.getActivitySessionId(),"items",items)).andExpect(status().isCreated());
        return objectMapper.readTree(response.andReturn().getResponse().getContentAsString()).path("orderId").asLong();
    }
    ResultActions pay(long id) throws Exception { return mvc.perform(post("/ticket-orders/{id}/pay",id).header(HttpHeaders.AUTHORIZATION,bearer(buyer))); }
    long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    long shortEvent() throws Exception { var input=body(30); input.put("newStartTime",offset(baseTime.plusSeconds(10))); input.put("newEndTime",offset(baseTime.plusHours(2))); return objectMapper.readTree(reschedule(input,publisher).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("eventId").asLong(); }
    void awaitWindow(long event) throws Exception {
        var deadline=events.event(event).getSpecialRefundDeadline(); long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(databaseTime.currentTime().isBefore(deadline) && System.nanoTime()<until) Thread.sleep(30);
        assertThat(databaseTime.currentTime()).isAfterOrEqualTo(deadline);
    }
    void awaitCount(String sql,long expected) throws Exception { long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20); while(System.nanoTime()<until && count(sql)!=expected) Thread.sleep(40); assertThat(count(sql)).isEqualTo(expected); }
    int unusedPort() throws Exception { try(var socket=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    void save(String name,Object data) throws Exception { var file=Path.of("target/day22/"+name+".json"); Files.createDirectories(file.getParent()); objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),data); }
    long observed(Probe probe,Callable<? extends Number> action) throws Exception { observation.current.set(probe); try { return action.call().longValue(); } finally { observation.current.remove(); } }
    static class Probe {
        final String suffix; final boolean before; final CountDownLatch entered=new CountDownLatch(1); final CountDownLatch release;
        final AtomicBoolean used=new AtomicBoolean(); volatile long connection; boolean fail; long rowsBeforeFailure;
        Probe(String suffix,boolean before,boolean hold) { this.suffix=suffix; this.before=before; this.release=new CountDownLatch(hold?1:0); }
    }
    static class InjectedFailure extends RuntimeException { }
    @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),
            @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
    static class Observation implements Interceptor {
        final ThreadLocal<Probe> current=new ThreadLocal<>(); final JdbcTemplate jdbc;
        Observation(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        @Override public Object intercept(Invocation call) throws Throwable {
            var probe=current.get(); boolean match=probe!=null && ((MappedStatement)call.getArgs()[0]).getId().endsWith(probe.suffix) && probe.used.compareAndSet(false,true);
            if(match && probe.before) pause(probe);
            Object result=call.proceed();
            if(match && !probe.before) pause(probe);
            return result;
        }
        void pause(Probe probe) throws Exception {
            probe.connection=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
            if(probe.fail) { probe.rowsBeforeFailure=jdbc.queryForObject("SELECT COUNT(*) FROM session_reschedule_event",Long.class); throw new InjectedFailure(); }
            probe.entered.countDown(); assertThat(probe.release.await(15,TimeUnit.SECONDS)).isTrue();
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class ObservationConfiguration {
        @Bean Observation day22Observation(JdbcTemplate jdbc) { return new Observation(jdbc); }
    }
}
