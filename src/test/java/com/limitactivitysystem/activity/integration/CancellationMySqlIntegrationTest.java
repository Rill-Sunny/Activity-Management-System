package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.cancellation.*;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.messaging.*;
import com.limitactivitysystem.outbox.*;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.support.*;
import com.limitactivitysystem.ticketorder.mapper.*;
import com.limitactivitysystem.ticketorder.model.Ticket;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.limitactivitysystem.activity.integration.SessionRescheduleMySqlIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={JwtTestTokens.SECRET_PROPERTY,"spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import({CurrentSchemaTestConfiguration.class,IsolatedRabbitTestConfiguration.class,ObservationConfiguration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@RequiresEnvironmentVariable(named="RUN_DAY23_TESTS",matches="true")
class CancellationMySqlIntegrationTest extends PaymentResourceTestSupport {
    static final String PREFIX="it.cancellation."+UUID.randomUUID().toString().replace("-","")+".";
    static final Map<String,Object> AUDIT=new ConcurrentSkipListMap<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("messaging.rabbit.name-prefix",()->PREFIX);}
    @Autowired CancellationMapper cancellations;
    @MockitoSpyBean CancellationBatchService batches;
    @Autowired CancellationWorker worker;
    @MockitoSpyBean CancellationItemProcessor processor;
    @Autowired TicketMapper ticketMapper;
    @Autowired DatabaseTimeMapper time;
    @Autowired IsolatedRabbitTestConfiguration rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired OutboxClaimService claims;
    @Autowired OutboxRelayService relay;
    @Autowired OutboxTraceSupport trace;
    @Autowired com.limitactivitysystem.qualification.schedule.QualificationAfterSaleTaskRetryJob afterSaleRetry;
    @Autowired Observation observation;
    Activity activity;ActivitySession session,other;SessionZone zone,otherZone,gift;
    long orderId;List<Ticket> sold;LocalDateTime now;

    @BeforeEach void setup() throws Exception {
        now=time.currentTime();activity=insertDraft(publisher,"PAID");
        jdbc.update("UPDATE activity SET open_time=?,close_time=? WHERE activity_id=?",now.minusDays(10),now.plusDays(1),activity.getActivityId());
        session=insertSession(activity);other=insertSession(activity);
        for(var s:List.of(session,other))jdbc.update("UPDATE activity_session SET start_time=?,end_time=?,capacity=12,max_tickets_per_user=10 WHERE activity_session_id=?",now.plusDays(20),now.plusDays(20).plusHours(2),s.getActivitySessionId());
        zone=insertZone(session,"PAID",10,10);gift=insertZone(session,"GIFT",2,2);otherZone=insertZone(other,"PAID",12,12);
        jdbc.update("UPDATE session_zone SET price=123.45 WHERE zone_id=?",zone.getZoneId());
        publish(activity,publisher).andExpect(status().isNoContent());
        orderId=newOrder(session,zone,0,1,2);pay(orderId).andExpect(status().isOk());sold=ticketMapper.findByOrderId(orderId);
    }
    @AfterEach void audit(TestInfo info) throws Exception {
        observation.current.remove();reset(processor);rabbit.cleanup(info.getTestMethod().orElseThrow().getName());
        try {
            // A crash between item commit and summary refresh is recoverable; refresh before quiescent reconciliation.
            for(long id:jdbc.queryForList("SELECT batch_id FROM cancellation_batch",Long.class))batches.summarize(id);
            var snapshot=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            snapshot.setReadOnly(true);snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var differences=snapshot.execute(status->{
                var result=new ArrayList<Map<String,Object>>();
                try(var paths=Files.list(Path.of("tests/sql"))) {
                    for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList()) {
                        for(String sql:Files.readString(path).replaceAll("(?m)--[^\\r\\n]*","").split(";"))if(!sql.isBlank()) {
                            var rows=jdbc.queryForList(sql);if(!path.getFileName().toString().equals("messaging_reconciliation.sql"))result.addAll(rows);
                        }
                    }
                }catch(java.io.IOException ex){throw new IllegalStateException(ex);}return result;
            });
            AUDIT.put(info.getDisplayName(),Map.of("differences",differences,"events",jdbc.queryForList("SELECT * FROM cancellation_event"),
                    "batches",jdbc.queryForList("SELECT * FROM cancellation_batch"),"items",jdbc.queryForList("SELECT * FROM cancellation_batch_item"),
                    "refunds",jdbc.queryForList("SELECT * FROM ticket_refund_request")));
            assertThat(differences).as(info.getDisplayName()).isEmpty();
        } finally {
            for(String table:List.of("cancellation_batch_item","cancellation_batch","cancellation_scope","cancellation_event",
                    "reschedule_refund_link","reschedule_refund_submission","reschedule_response","reschedule_affected_ticket","session_reschedule_event",
                    "ticket_refund_item","ticket_refund_request","gift_inventory_operation_item","gift_inventory_operation","free_reservation","free_session_quota",
                    "ticket_qualification_after_sale_task","product_after_sale_item","product_after_sale_request","product_order_item","product_order","user_product_purchase",
                    "product_whitelist_member","sku","product","whitelist_member","whitelist_config","notification","consumer_failure","consumer_process","outbox_event")) jdbc.update("DELETE FROM "+table);
        }
    }
    @AfterAll static void saveAudit() throws Exception {
        var file=Path.of("target/day23/reconciliation.json");Files.createDirectories(file.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),AUDIT);
    }

    @Test void boundedGenerationAdvancesEmptyPhasesAndResumesFromCommittedCursor() throws Exception {
        for(int n=0;n<51;n++)newOrder(session,zone,6);
        long event=cancelSession();
        worker.runOnce();
        var first=cancellations.batchForEvent(event);
        // Two ORDER pages, one TICKET page, and an empty RESERVATION page exhaust this tick.
        assertThat(first.scanPhase()).isEqualTo(3);
        assertThat(first.totalItems()).isEqualTo(55);
        assertThat(first.succeededItems()).isEqualTo(50);
        assertThat(first.status()).isEqualTo("PROCESSING");
        worker.runOnce();
        var complete=cancellations.batchForEvent(event);
        assertThat(complete.scanPhase()).isEqualTo(4);
        assertThat(complete.status()).isEqualTo("COMPLETED");
        assertThat(complete.totalItems()).isEqualTo(55);
        assertThat(complete.succeededItems()).isEqualTo(55);
        assertThat(count("SELECT COUNT(*) FROM ticket_order WHERE status='CLOSED'")).isEqualTo(51);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request WHERE status='SUCCEEDED'")).isEqualTo(3);
        worker.runOnce();
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request")).isEqualTo(3);
        save("bounded-generation",Map.of("firstTick",first,"completed",complete));
    }
    @Test void at19SessionCancellationClosesOnlyItsOrdersAndRefundsOnlyValidTickets() throws Exception {
        long original=ordinary(sold.getFirst().getTicketId());processRefund(original).andExpect(status().isOk());
        long extra=newOrder(session,zone,3,4);pay(extra).andExpect(status().isOk());
        long pending=newOrder(session,zone,5),pending2=newOrder(session,zone,6);
        long otherOrder=newOrder(other,otherZone,0);pay(otherOrder).andExpect(status().isOk());
        var beforeOther=jdbc.queryForMap("SELECT * FROM ticket_order WHERE order_id=?",otherOrder);
        postJson("/publisher/gift-zones/"+gift.getZoneId()+"/allocations",publisher,Map.of("requestId","gift","quantity",2)).andExpect(status().isCreated());
        long event=cancelSession();assertThat(cancellations.batchForEvent(event).totalItems()).isZero();
        pay(pending).andExpect(status().isConflict());
        postOrder(session,zone,7).andExpect(status().isConflict());
        postJson("/publisher/gift-zones/"+gift.getZoneId()+"/allocations",publisher,Map.of("requestId","late-gift","quantity",1)).andExpect(status().isConflict());
        long giftItem=jdbc.queryForObject("SELECT MIN(operation_item_id) FROM gift_inventory_operation_item",Long.class);
        mvc.perform(get("/publisher/gift-items/{id}/participation",giftItem).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(jsonPath("$.effective").value(false));
        drain(event);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request WHERE refund_policy='CANCELLATION_REFUND' AND status='SUCCEEDED'")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_item WHERE refund_id=?",original)).isEqualTo(1);
        assertThat(orders.findById(pending2).getClosedReason()).isEqualTo("SESSION_CANCELLED");
        assertThat(jdbc.queryForMap("SELECT * FROM ticket_order WHERE order_id=?",otherOrder)).isEqualTo(beforeOther);
        assertThat(sessionMapper.findById(other.getActivitySessionId()).getStatus()).isEqualTo("SCHEDULED");
        assertThat(count("SELECT COUNT(*) FROM session_seat WHERE zone_id=? AND allocation_status='GIFT_CANCELLED'",gift.getZoneId())).isEqualTo(2);
        assertThat(count("SELECT remains_amount FROM session_zone WHERE zone_id=?",gift.getZoneId())).isZero();
        postOrder(session,zone,0).andExpect(status().isConflict());
    }
    @Test void at20OneRefundRollsBackAndOthersCommitThenOnlyTheFailedItemRetries() throws Exception {
        long bad=sold.get(1).getTicketId();
        doAnswer(call->{var item=call.getArgument(0,CancellationMapper.Item.class);
            if(item.targetType().equals("TICKET")&&item.targetId()==bad){var probe=new Probe("RefundTicketMapper.markRefunded",true,false);probe.fail=true;observation.current.set(probe);}
            try{return call.callRealMethod();}finally{observation.current.remove();}
        }).when(processor).process(any());
        long event=cancelSession();for(int n=0;n<4;n++)worker.runOnce();
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request WHERE status='SUCCEEDED'")).isEqualTo(2);
        var item=jdbc.queryForMap("SELECT * FROM cancellation_batch_item WHERE target_type='TICKET' AND target_id=?",bad);
        assertThat(item.get("status")).isEqualTo("RETRY_PENDING");assertThat(item.get("failure_reason")).isNotNull();
        assertThat(count("SELECT valid_count FROM user_session_purchase WHERE user_id=? AND session_id=?",buyer.getId(),session.getActivitySessionId())).isEqualTo(1);
        save("at20-failed",item);reset(processor);
        for(int n=0;n<2;n++)mvc.perform(post("/publisher/cancellations/{id}/items/{item}/retry",event,item.get("item_id")).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isAccepted());
        drain(event);worker.runOnce();
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request")).isEqualTo(3);

        assertThat(jdbc.queryForObject("SELECT SUM(refund_amount) FROM ticket_refund_item WHERE status='SUCCEEDED'",java.math.BigDecimal.class)).isEqualByComparingTo("370.35");
    }
    @Test void activityCancellationPreservesFinishedHistoryAndCoversRemainingSessions() throws Exception {
        long historical=newOrder(other,otherZone,0);pay(historical).andExpect(status().isOk());
        jdbc.update("UPDATE activity_session SET status='FINISHED',start_time=?,end_time=? WHERE activity_session_id=?",now.minusDays(2),now.minusDays(1),other.getActivitySessionId());
        var before=jdbc.queryForMap("SELECT * FROM ticket_order WHERE order_id=?",historical);
        long event=cancel(true,activity.getActivityId(),"all");drain(event);
        assertThat(cancellations.sessions(event)).containsExactly(session.getActivitySessionId());
        assertThat(jdbc.queryForMap("SELECT * FROM ticket_order WHERE order_id=?",historical)).isEqualTo(before);
        mvc.perform(get("/activities/{id}",activity.getActivityId())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/activities/{id}/cancellation",activity.getActivityId())).andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("venue unavailable"));
    }
    @Test void activityAfterSessionCancellationDoesNotDuplicateItsRefunds() throws Exception {
        long first=cancelSession();drain(first);long second=cancel(true,activity.getActivityId(),"all");drain(second);
        assertThat(cancellations.sessions(second)).containsExactly(other.getActivitySessionId());
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request")).isEqualTo(3);
    }
    @Test void pendingOrdinaryQuoteIsPreservedAndSupersededByFullRefund() throws Exception {
        long old=ordinary(sold.getFirst().getTicketId());var quote=jdbc.queryForObject("SELECT refund_amount FROM ticket_refund_item WHERE refund_id=?",java.math.BigDecimal.class,old);
        long event=cancelSession();processRefund(old).andExpect(status().isConflict());drain(event);
        assertThat(jdbc.queryForObject("SELECT result FROM ticket_refund_request WHERE refund_id=?",String.class,old)).isEqualTo("CANCELLATION_SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM ticket_refund_item WHERE refund_id=?",java.math.BigDecimal.class,old)).isEqualByComparingTo(quote);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request WHERE status='SUCCEEDED'")).isEqualTo(3);
    }
    @Test void buyerChosenRefundKeyCannotBlockAutomaticCancellationRefund() throws Exception {
        long expected=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>)connection->{
            try(var statement=connection.createStatement()) {
                statement.execute("SET SESSION information_schema_stats_expiry=0");
                try(var rows=statement.executeQuery("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='cancellation_event'")) {
                    assertThat(rows.next()).isTrue();return rows.getLong(1);
                }finally{statement.execute("SET SESSION information_schema_stats_expiry=DEFAULT");}
            }
        });
        String chosen="cancel:"+expected+":"+sold.getFirst().getTicketId();
        postJson("/ticket-refunds",buyer,Map.of("requestId",chosen,"orderId",orderId,"ticketIds",List.of(sold.getFirst().getTicketId()),"reason","ordinary"))
                .andExpect(status().isCreated());
        long event=cancelSession();assertThat(event).isEqualTo(expected);drain(event);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request WHERE refund_policy='CANCELLATION_REFUND' AND status='SUCCEEDED'")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT result FROM ticket_refund_request WHERE buyer_id=? AND request_id=?",String.class,buyer.getId(),chosen)).isEqualTo("CANCELLATION_SUPERSEDED");
    }
    @Test void rescheduleHistoryAndResponseSurviveCancellation() throws Exception {
        var body=Map.of("requestId","move","newStartTime",now.plusDays(30).atOffset(ZoneOffset.ofHours(8)).toString(),"newEndTime",now.plusDays(30).plusHours(2).atOffset(ZoneOffset.ofHours(8)).toString(),"reason","move");
        long event=objectMapper.readTree(postJson("/publisher/sessions/"+session.getActivitySessionId()+"/reschedules",publisher,body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("eventId").asLong();
        postJson("/reschedules/"+event+"/confirm",buyer,Map.of("ticketIds",List.of(sold.getFirst().getTicketId()))).andExpect(status().isOk());
        var before=jdbc.queryForList("SELECT * FROM reschedule_response");drain(cancelSession());
        assertThat(jdbc.queryForList("SELECT * FROM reschedule_response")).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM session_reschedule_event")).isEqualTo(1);
        postJson("/publisher/sessions/"+session.getActivitySessionId()+"/reschedules",publisher,new HashMap<>(body){{put("requestId","late");}}).andExpect(status().isConflict());
    }
    @Test void ongoingSessionStillReceivesFullCancellationRefundAndDisabledBuyerIsNotBlocked() throws Exception {
        jdbc.update("UPDATE activity_session SET start_time=?,end_time=? WHERE activity_session_id=?",now.minusMinutes(5),now.plusHours(1),session.getActivitySessionId());
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",buyer.getId());
        drain(cancelSession());assertThat(count("SELECT COUNT(*) FROM ticket_refund_request WHERE status='SUCCEEDED'")).isEqualTo(3);
    }
    @Test void publisherAuthenticationIdempotencyAndScopeAreEnforced() throws Exception {
        var body=Map.of("requestId","same","reason","venue unavailable");String url="/publisher/sessions/"+session.getActivitySessionId()+"/cancel";
        postJson(url,buyer,body).andExpect(status().isForbidden());postJson(url,otherPublisher,body).andExpect(status().isForbidden());
        postJson(url,publisher,Map.of("requestId","spoof","reason","test","publisherId",publisher.getId())).andExpect(status().isBadRequest());
        long event=cancel(false,session.getActivitySessionId(),"same");assertThat(cancel(false,session.getActivitySessionId(),"same")).isEqualTo(event);
        postJson(url,publisher,Map.of("requestId","another","reason","other")).andExpect(status().isConflict());
        mvc.perform(get("/publisher/cancellations/{id}",event).header(HttpHeaders.AUTHORIZATION,bearer(otherPublisher))).andExpect(status().isForbidden());
        mvc.perform(get("/publisher/cancellations/{id}",event).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isOk());drain(event);
    }
    @Test void cancellingAnEndedSessionIsRejected() throws Exception {
        jdbc.update("UPDATE activity_session SET start_time=?,end_time=? WHERE activity_session_id=?",now.minusDays(2),now.minusDays(1),session.getActivitySessionId());
        postJson("/publisher/sessions/"+session.getActivitySessionId()+"/cancel",publisher,Map.of("requestId","ended","reason","test")).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM cancellation_event")).isZero();
    }
    @Test void eventGateBatchAndOutboxRollbackTogether() throws Exception {
        var probe=new Probe("CancellationMapper.cancelSessions",true,false);probe.fail=true;observation.current.set(probe);
        try{postJson("/publisher/sessions/"+session.getActivitySessionId()+"/cancel",publisher,Map.of("requestId","rollback","reason","test")).andExpect(status().isServiceUnavailable());}
        finally{observation.current.remove();}
        assertThat(count("SELECT COUNT(*) FROM cancellation_event")).isZero();assertThat(count("SELECT COUNT(*) FROM cancellation_scope")).isZero();
        assertThat(sessionMapper.findById(session.getActivitySessionId()).getStatus()).isEqualTo("SCHEDULED");
    }
    @Test void stableCursorCrossesFiftyRowsWithoutSkippingClosedOrders() throws Exception {
        for(int i=0;i<53;i++)newOrder(session,zone,6);
        long event=cancelSession();long batch=cancellations.batchForEvent(event).batchId();batches.generate(batch);
        assertThat(cancellations.batchForEvent(event).nextCursor()).isPositive();worker.runOnce();drain(event);
        assertThat(count("SELECT COUNT(*) FROM cancellation_batch_item WHERE target_type='ORDER'")).isEqualTo(54);
        assertThat(count("SELECT COUNT(*) FROM ticket_order WHERE status='PENDING_PAYMENT'")).isZero();
    }
    @Test void abandonedLeaseIsTakenOverAndOldTokenCannotApplyEffects() throws Exception {
        long event=cancelSession();long batch=cancellations.batchForEvent(event).batchId();batches.generate(batch);
        var old=batches.claim();assertThat(old).isNotNull();
        jdbc.update("UPDATE cancellation_batch_item SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE item_id=?",old.itemId());
        var next=batches.claim();assertThat(next.itemId()).isEqualTo(old.itemId());assertThat(next.claimToken()).isNotEqualTo(old.claimToken());
        processor.process(old);assertThat(jdbc.queryForObject("SELECT status FROM cancellation_batch_item WHERE item_id=?",String.class,old.itemId())).isEqualTo("PROCESSING");
        processor.process(next);drain(event);
    }
    @Test void finiteFailuresRequireExplicitRetryAndRemainQueryable() throws Exception {
        long event=cancelSession();batches.generate(cancellations.batchForEvent(event).batchId());
        long id=jdbc.queryForObject("SELECT MIN(item_id) FROM cancellation_batch_item",Long.class);
        for(int i=0;i<5;i++){var item=batches.claim();assertThat(item.itemId()).isEqualTo(id);batches.failed(item,new IllegalStateException());jdbc.update("UPDATE cancellation_batch_item SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE item_id=?",id);}
        for(int i=0;i<4;i++)worker.runOnce();assertThat(cancellations.batchForEvent(event).status()).isEqualTo("PARTIAL_FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM cancellation_batch_item WHERE item_id=?",Integer.class,id)).isEqualTo(5);
        mvc.perform(post("/publisher/cancellations/{id}/items/{item}/retry",event,id).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isAccepted());drain(event);
    }

    @Test void freeReservationLosesParticipationBeforeWorkerAndRestoresQuotaOnce() throws Exception {
        var free=freeSession();reserve(free).andExpect(status().isCreated());
        long reservation=jdbc.queryForObject("SELECT reservation_id FROM free_reservation WHERE session_id=?",Long.class,free.getActivitySessionId());
        long event=cancel(false,free.getActivitySessionId(),"free");
        mvc.perform(get("/reservations/{id}/participation",reservation).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isOk()).andExpect(jsonPath("$.effective").value(false));
        assertThat(jdbc.queryForObject("SELECT status FROM free_reservation WHERE reservation_id=?",String.class,reservation)).isEqualTo("ACTIVE");
        reserve(free).andExpect(status().isConflict());drain(event);worker.runOnce();
        assertThat(count("SELECT remaining FROM free_session_quota WHERE session_id=?",free.getActivitySessionId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT status FROM free_reservation WHERE reservation_id=?",String.class,reservation)).isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE status='VALID'")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationAndPaymentSerializeAtActualDatabaseGate(boolean payFirst) throws Exception {
        long pending=newOrder(session,zone,3);
        var holder=new Probe(payFirst?"ActivityMapper.findByIdForShare":"CancellationMapper.cancelSessions",false,true);
        var waiter=new Probe(payFirst?"ActivityMapper.findByIdForUpdate":"ActivityMapper.findByIdForShare",true,false);
        var first=async(()->observed(holder,()->payFirst?pay(pending).andExpect(status().isOk()).andReturn().getResponse().getStatus():cancelSession()));
        await(holder.entered);
        var second=async(()->observed(waiter,()->payFirst?cancelSession():pay(pending).andReturn().getResponse().getStatus()));
        try{await(waiter.entered);save("payment-cutoff-"+payFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity"));}finally{holder.release.countDown();}
        long a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);long event=payFirst?b:a;
        if(!payFirst)assertThat(b).isEqualTo(409);drain(event);
        assertThat(ticketMapper.findByOrderId(pending)).hasSize(payFirst?1:0);
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE status='VALID'")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationAndReservationSerializeAtActualDatabaseGate(boolean reserveFirst) throws Exception {
        var free=freeSession();
        var holder=new Probe(reserveFirst?"ActivityMapper.findByIdForShare":"CancellationMapper.cancelSessions",false,true);
        var waiter=new Probe(reserveFirst?"ActivityMapper.findByIdForUpdate":"ActivityMapper.findByIdForShare",true,false);
        var first=async(()->observed(holder,()->reserveFirst?reserve(free).andExpect(status().isCreated()).andReturn().getResponse().getStatus():cancel(false,free.getActivitySessionId(),"free-race")));
        await(holder.entered);
        var second=async(()->observed(waiter,()->reserveFirst?cancel(false,free.getActivitySessionId(),"free-race"):reserve(free).andReturn().getResponse().getStatus()));
        try{await(waiter.entered);save("reservation-cutoff-"+reserveFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity"));}finally{holder.release.countDown();}
        long a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);if(!reserveFirst)assertThat(b).isEqualTo(409);
        drain(reserveFirst?b:a);assertThat(count("SELECT COUNT(*) FROM free_reservation WHERE status='ACTIVE'")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationAndRefundSerializeWithExactlyOneSuccessfulEffect(boolean refundFirst) throws Exception {
        long refund=ordinary(sold.getFirst().getTicketId());
        var holder=new Probe(refundFirst?"ActivityMapper.findByIdForShare":"CancellationMapper.cancelSessions",false,true);
        var waiter=new Probe(refundFirst?"ActivityMapper.findByIdForUpdate":"ActivityMapper.findByIdForShare",true,false);
        var first=async(()->observed(holder,()->refundFirst?processRefund(refund).andExpect(status().isOk()).andReturn().getResponse().getStatus():cancelSession()));
        await(holder.entered);var second=async(()->observed(waiter,()->refundFirst?cancelSession():processRefund(refund).andReturn().getResponse().getStatus()));
        try{await(waiter.entered);save("refund-cutoff-"+refundFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity"));}finally{holder.release.countDown();}
        long a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);if(!refundFirst)assertThat(b).isEqualTo(409);
        drain(refundFirst?b:a);assertThat(count("SELECT COUNT(*) FROM ticket_refund_item WHERE status='SUCCEEDED'")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationAndGiftAllocationUseTheSameLifecycleGate(boolean giftFirst) throws Exception {
        var holder=new Probe(giftFirst?"ActivityMapper.findByIdForShare":"CancellationMapper.cancelSessions",false,true);
        var waiter=new Probe(giftFirst?"ActivityMapper.findByIdForUpdate":"ActivityMapper.findByIdForShare",true,false);
        Callable<Long> allocate=()->(long)postJson("/publisher/gift-zones/"+gift.getZoneId()+"/allocations",publisher,Map.of("requestId","gift-race","quantity",2)).andReturn().getResponse().getStatus();
        var first=async(()->observed(holder,()->giftFirst?allocate.call():cancelSession()));await(holder.entered);
        var second=async(()->observed(waiter,()->giftFirst?cancelSession():allocate.call()));
        try{await(waiter.entered);save("gift-cutoff-"+giftFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity"));}finally{holder.release.countDown();}
        long a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);assertThat(giftFirst?a:b).isEqualTo(giftFirst?201:409);
        drain(giftFirst?b:a);assertThat(count("SELECT COUNT(*) FROM session_seat WHERE allocation_status='GIFT_ALLOCATED'")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationAndReschedulePreserveOnlyCommittedHistory(boolean rescheduleFirst) throws Exception {
        var holder=new Probe(rescheduleFirst?"ActivityMapper.findByIdForShare":"CancellationMapper.cancelSessions",false,true);
        var waiter=new Probe(rescheduleFirst?"ActivityMapper.findByIdForUpdate":"ActivityMapper.findByIdForShare",true,false);
        Callable<Long> move=()->(long)postJson("/publisher/sessions/"+session.getActivitySessionId()+"/reschedules",publisher,
                Map.of("requestId","race-move","reason","test","newStartTime",now.plusDays(30).atOffset(ZoneOffset.ofHours(8)).toString(),"newEndTime",now.plusDays(30).plusHours(2).atOffset(ZoneOffset.ofHours(8)).toString())).andReturn().getResponse().getStatus();
        var first=async(()->observed(holder,()->rescheduleFirst?move.call():cancelSession()));await(holder.entered);
        var second=async(()->observed(waiter,()->rescheduleFirst?cancelSession():move.call()));
        try{await(waiter.entered);save("reschedule-cutoff-"+rescheduleFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity"));}finally{holder.release.countDown();}
        long a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);assertThat(rescheduleFirst?a:b).isEqualTo(rescheduleFirst?201:409);
        drain(rescheduleFirst?b:a);assertThat(count("SELECT COUNT(*) FROM session_reschedule_event")).isEqualTo(rescheduleFirst?1:0);
    }
    @Test void committedCancellationSurvivesRelayFailureConsumerStopAndDuplicateRabbitDelivery() throws Exception {
        rabbit.prepare();long event=cancelSession();
        int unused;try(var socket=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())){unused=socket.getLocalPort();}
        var unavailable=new CachingConnectionFactory("127.0.0.1",unused);unavailable.setConnectionTimeout(500);
        unavailable.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);unavailable.setPublisherReturns(true);
        try {var template=new RabbitTemplate(unavailable);template.setMandatory(true);
            var sender=new OutboxPublisher(template,objectMapper,time,trace,1000,PREFIX);
            new OutboxRelayService(claims,sender,50,8,60).publishClaimedEvents();
            assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='SESSION_CANCELLED' AND status='PENDING' AND attempts=1")).isEqualTo(1);
            postOrder(session,zone,5).andExpect(status().isConflict());save("relay-failure",jdbc.queryForList("SELECT event_id,event_type,status,attempts FROM outbox_event"));
        }finally{unavailable.destroy();}
        jdbc.update("UPDATE outbox_event SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE status='PENDING'");relay.publishClaimedEvents();
        assertThat(cancellations.batchForEvent(event).totalItems()).isZero();
        String message=jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_type='SESSION_CANCELLED'",String.class);
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?",String.class,message);
        var properties=new org.springframework.amqp.core.MessageProperties();properties.setHeader("x-event-id",message);properties.setMessageId(message);
        rabbitTemplate.send(PREFIX+RabbitMqTopology.EVENTS_EXCHANGE,"cancellation.execute",new org.springframework.amqp.core.Message(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),properties));
        listeners.start();awaitCount("SELECT COUNT(*) FROM consumer_process WHERE consumer_name='cancellation-generation-v1'",1);
        verify(batches,timeout(5000).atLeast(2)).receive(message,event);listeners.stop();drain(event);
        assertThat(count("SELECT COUNT(*) FROM ticket_refund_request")).isEqualTo(3);
        save("relay-recovery",jdbc.queryForList("SELECT event_id,event_type,status,attempts FROM outbox_event"));
    }
    @Test void scannerRepairsSummaryAfterBusinessCommitWithoutAckOrWorkerContinuation() throws Exception {
        long event=cancelSession(),batch=cancellations.batchForEvent(event).batchId();for(int i=0;i<4;i++)batches.generate(batch);
        CancellationMapper.Item item;while((item=batches.claim())!=null)processor.process(item);
        assertThat(cancellations.batchForEvent(event).status()).isEqualTo("PROCESSING");worker.runOnce();
        assertThat(cancellations.batchForEvent(event).status()).isEqualTo("COMPLETED");
    }
    @Test void twoWorkersProcessOneBatchWithoutDuplicateRefunds() throws Exception {
        long event=cancelSession();var barrier=new CyclicBarrier(2);
        Callable<Boolean> run=()->{barrier.await(10,TimeUnit.SECONDS);for(int n=0;n<6;n++)worker.runOnce();return true;};
        var a=async(run);var b=async(run);assertThat(a.get(20,TimeUnit.SECONDS)).isTrue();assertThat(b.get(20,TimeUnit.SECONDS)).isTrue();
        drain(event);assertThat(count("SELECT COUNT(*) FROM ticket_refund_item WHERE status='SUCCEEDED'")).isEqualTo(3);
        assertThat(count("SELECT MAX(attempt_count) FROM cancellation_batch_item")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void activityWideProductQualificationCannotRacePastCancellation(boolean paymentFirst) throws Exception {
        long sku=productSku("TICKET_REQUIRED","ACTIVITY",null);
        long productOrder=objectMapper.readTree(postJson("/product-orders/confirm",buyer,Map.of("requestId","product-race","items",List.of(Map.of("skuId",sku,"quantity",1))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("orderId").asLong();
        var holder=new Probe(paymentFirst?"ActivityMapper.findByIdForShare":"CancellationMapper.cancelSessions",false,true);
        var waiter=new Probe(paymentFirst?"ActivityMapper.findByIdForUpdate":"ActivityMapper.findByIdForShare",true,false);
        Callable<Long> payment=()->{
            var result=postJson("/product-orders/"+productOrder+"/pay",buyer,Map.of());
            if(!paymentFirst)result.andExpect(jsonPath("$.code").value("PRODUCT_QUALIFICATION_DENIED"));
            return (long)result.andReturn().getResponse().getStatus();
        };
        var first=async(()->observed(holder,()->paymentFirst?payment.call():cancelSession()));await(holder.entered);
        var second=async(()->observed(waiter,()->paymentFirst?cancelSession():payment.call()));
        try{await(waiter.entered);save("product-cutoff-"+paymentFirst,awaitDatabaseLockWait(waiter.connection,holder.connection,"activity"));}finally{holder.release.countDown();}
        long a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);assertThat(paymentFirst?a:b).isEqualTo(paymentFirst?200:403);
        drain(paymentFirst?b:a);assertThat(count("SELECT remains_stock FROM sku WHERE sku_id=?",sku)).isEqualTo(paymentFirst?1:2);
    }
    @ParameterizedTest @ValueSource(strings={"enable","add","remove","disable"})
    void cancelledSessionWhitelistCannotBeModified(String action) throws Exception {
        String path="/publisher/sessions/"+session.getActivitySessionId()+"/whitelist";
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isOk());
        postJson(path+"/members",publisher,Map.of("userId",buyer.getId())).andExpect(status().isCreated());
        var config=jdbc.queryForList("SELECT * FROM whitelist_config");var members=jdbc.queryForList("SELECT * FROM whitelist_member");
        long event=cancelSession();
        switch(action) {
            case "enable" -> mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isConflict());
            case "add" -> postJson(path+"/members",publisher,Map.of("userId",otherPublisher.getId())).andExpect(status().isConflict());
            case "remove" -> mvc.perform(delete(path+"/members/"+buyer.getId()).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isConflict());
            case "disable" -> mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForList("SELECT * FROM whitelist_config")).isEqualTo(config);assertThat(jdbc.queryForList("SELECT * FROM whitelist_member")).isEqualTo(members);
        mvc.perform(put("/publisher/sessions/{id}/whitelist",other.getActivitySessionId()).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isOk());
        drain(event);
    }
    @Test void whitelistRechecksSessionAfterWaitingForCancellationRootLock() throws Exception {
        var holder=new Probe("CancellationMapper.cancelSessions",false,true);var waiter=new Probe("ActivityMapper.findByIdForUpdate",true,false);
        var cancel=async(()->observed(holder,this::cancelSession));await(holder.entered);
        var edit=async(()->observed(waiter,()->mvc.perform(put("/publisher/sessions/{id}/whitelist",session.getActivitySessionId()).header(HttpHeaders.AUTHORIZATION,bearer(publisher)))
                .andReturn().getResponse().getStatus()));
        try{await(waiter.entered);save("whitelist-cutoff",awaitDatabaseLockWait(waiter.connection,holder.connection,"activity"));}finally{holder.release.countDown();}
        long event=cancel.get(20,TimeUnit.SECONDS);assertThat(edit.get(20,TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM whitelist_config")).isZero();drain(event);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationUsesRealRabbitQualificationForOnlyTheAffectedMixedOrderLine(boolean shipped) throws Exception {
        rabbit.prepare();long otherTicketOrder=newOrder(other,otherZone,0);pay(otherTicketOrder).andExpect(status().isOk());
        long limited=productSku("TICKET_REQUIRED","SESSION",session.getActivitySessionId());
        long normal=productSku("PUBLIC",null,null),otherLimited=productSku("TICKET_REQUIRED","SESSION",other.getActivitySessionId());
        long activityLimited=productSku("TICKET_REQUIRED","ACTIVITY",null);
        var json=objectMapper.readTree(postJson("/product-orders/confirm",buyer,Map.of("requestId","mixed","items",List.of(
                Map.of("skuId",limited,"quantity",1),Map.of("skuId",normal,"quantity",1),Map.of("skuId",otherLimited,"quantity",1),Map.of("skuId",activityLimited,"quantity",1))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long productOrder=json.path("orderId").asLong();postJson("/product-orders/"+productOrder+"/pay",buyer,Map.of()).andExpect(status().isOk());
        long item=jdbc.queryForObject("SELECT order_item_id FROM product_order_item WHERE order_id=? AND sku_id=?",Long.class,productOrder,limited);
        if(shipped)postJson("/publisher/product-orders/"+productOrder+"/ship",publisher,Map.of("orderItemId",item,"quantity",1)).andExpect(status().isOk());
        long event=cancelSession();drain(event);
        mvc.perform(get("/products/{id}/qualification",jdbc.queryForObject("SELECT product_id FROM sku WHERE sku_id=?",Long.class,limited)).header(HttpHeaders.AUTHORIZATION,bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
        listeners.start();relay.publishClaimedEvents();
        awaitCount("SELECT COUNT(*) FROM ticket_qualification_after_sale_task WHERE status='"+(shipped?"WAITING_RETURN":"SUCCEEDED")+"'",1);
        awaitCount("SELECT COUNT(*) FROM consumer_process WHERE consumer_name='ticket-qualification-after-sale-v1'",3);
        listeners.stop();
        assertThat(count("SELECT COUNT(*) FROM ticket_qualification_after_sale_task")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product_order_item WHERE order_id=? AND sku_id<>? AND refunded_quantity=0",productOrder,limited)).isEqualTo(3);
        assertThat(count("SELECT remains_stock FROM sku WHERE sku_id=?",limited)).isEqualTo(shipped?1:2);
        if(shipped) {
            assertThat(count("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",item)).isZero();
            postJson("/product-orders/"+productOrder+"/receive",buyer,Map.of("orderItemId",item,"quantity",1)).andExpect(status().isOk());
            afterSaleRetry.retryDueTasks();assertThat(count("SELECT remains_stock FROM sku WHERE sku_id=?",limited)).isEqualTo(2);
        }
        assertThat(count("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",item)).isEqualTo(1);
        assertThat(ticketMapper.findByOrderId(otherTicketOrder).getFirst().getStatus()).isEqualTo("VALID");
        save("mixed-order-"+shipped,Map.of("productItems",jdbc.queryForList("SELECT * FROM product_order_item"),"tasks",jdbc.queryForList("SELECT * FROM ticket_qualification_after_sale_task")));
    }

    long cancelSession() throws Exception{return cancel(false,session.getActivitySessionId(),UUID.randomUUID().toString());}
    ActivitySession freeSession() throws Exception {var a=insertDraft(publisher,"FREE");jdbc.update("UPDATE activity SET open_time=?,close_time=? WHERE activity_id=?",now.minusDays(1),now.plusDays(1),a.getActivityId());var s=insertSession(a);jdbc.update("UPDATE activity_session SET start_time=?,end_time=? WHERE activity_session_id=?",now.plusDays(20),now.plusDays(20).plusHours(2),s.getActivitySessionId());publish(a,publisher).andExpect(status().isNoContent());return s;}
    long productSku(String type,String scope,Long sessionId) throws Exception {
        var body=new HashMap<String,Object>();body.put("activityId",activity.getActivityId());body.put("name","cancel-test");body.put("description","isolation");
        body.put("qualificationType",type);body.put("qualificationScope",scope);body.put("qualificationSessionId",sessionId);body.put("purchaseLimit",2);body.put("refundPolicy","STANDARD_7D");
        long product=objectMapper.readTree(postJson("/publisher/products",publisher,body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("productId").asLong();
        long sku=objectMapper.readTree(postJson("/publisher/products/"+product+"/skus",publisher,Map.of("name","default","price",99,"stock",2)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("skuId").asLong();
        postJson("/publisher/products/"+product+"/publish",publisher,Map.of()).andExpect(status().isOk());return sku;
    }
    ResultActions reserve(ActivitySession s) throws Exception{return postJson("/sessions/"+s.getActivitySessionId()+"/reservations",buyer,Map.of());}
    long observed(Probe probe,Callable<? extends Number> action) throws Exception{observation.current.set(probe);try{return action.call().longValue();}finally{observation.current.remove();}}
    void awaitCount(String sql,long value) throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until&&count(sql)!=value)Thread.sleep(30);assertThat(count(sql)).isEqualTo(value);}
    long cancel(boolean all,long target,String key) throws Exception{return objectMapper.readTree(postJson("/publisher/"+(all?"activities/":"sessions/")+target+"/cancel",publisher,Map.of("requestId",key,"reason","venue unavailable")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("eventId").asLong();}
    void drain(long event){for(int n=0;n<20&&!cancellations.batchForEvent(event).status().equals("COMPLETED");n++)worker.runOnce();assertThat(cancellations.batchForEvent(event).status()).isEqualTo("COMPLETED");}
    ResultActions postOrder(ActivitySession s,SessionZone z,int... positions) throws Exception {
        var seats=seatMapper.findByZoneId(z.getZoneId());var items=new ArrayList<Map<String,Object>>();
        for(int p:positions)items.add(Map.of("zoneId",z.getZoneId(),"allocationMode","SELECT_SEAT","requestedSeatId",seats.get(p).getSeatId(),"quantity",1,"acceptNonAdjacent",false));
        return postJson("/ticket-orders",buyer,Map.of("requestId",UUID.randomUUID().toString(),"sessionId",s.getActivitySessionId(),"items",items));
    }
    long newOrder(ActivitySession s,SessionZone z,int... positions) throws Exception{return objectMapper.readTree(postOrder(s,z,positions).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("orderId").asLong();}
    ResultActions pay(long id) throws Exception{return mvc.perform(post("/ticket-orders/{id}/pay",id).header(HttpHeaders.AUTHORIZATION,bearer(buyer)));}
    long ordinary(long ticket) throws Exception{return objectMapper.readTree(postJson("/ticket-refunds",buyer,Map.of("requestId",UUID.randomUUID().toString(),"orderId",orderId,"ticketIds",List.of(ticket),"reason","ordinary")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("refundId").asLong();}
    ResultActions processRefund(long id) throws Exception{return mvc.perform(post("/ticket-refunds/{id}/process",id).header(HttpHeaders.AUTHORIZATION,bearer(buyer)));}
    long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
    void save(String name,Object data) throws Exception{var file=Path.of("target/day23/"+name+".json");Files.createDirectories(file.getParent());objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),data);}
}
