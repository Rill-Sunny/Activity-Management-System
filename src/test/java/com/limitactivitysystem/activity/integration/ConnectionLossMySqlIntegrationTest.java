package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.annotation.DirtiesContext;
import com.limitactivitysystem.support.RequiresEnvironmentVariable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL connection termination; the post-commit lost acknowledgement is injected explicitly. */
@SpringBootTest(properties={JwtTestTokens.SECRET_PROPERTY,"spring.flyway.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false","outbox.relay.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@Import(ConnectionLossMySqlIntegrationTest.Configuration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@RequiresEnvironmentVariable(named="DB_PASSWORD",matches=".+")
class ConnectionLossMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired LossSource source;

    @Test void connectionKilledBeforeCommitRollsBackAllPaymentResources() throws Exception { exercise(false); }
    @Test void lostCommitAcknowledgementRequiresQueryAndOriginalOrderRetry() throws Exception { exercise(true); }

    private void exercise(boolean afterCommit) throws Exception {
        var activity=insertDraft(publisher,"PAID");
        jdbc.update("UPDATE activity SET open_time=NOW(3)-INTERVAL 1 HOUR,close_time=NOW(3)+INTERVAL 1 HOUR WHERE activity_id=?",activity.getActivityId());
        var session=insertSession(activity);var zone=insertZone(session,"PAID",10,10);
        jdbc.update("UPDATE activity_session SET seat_mode='AUTO_ASSIGN' WHERE activity_session_id=?",session.getActivitySessionId());
        publish(activity,publisher).andExpect(status().isNoContent());
        var response=postJson("/ticket-orders",buyer,Map.of("requestId",UUID.randomUUID().toString(),"sessionId",session.getActivitySessionId(),
                "items",List.of(Map.of("zoneId",zone.getZoneId(),"allocationMode","AUTO_ASSIGN","quantity",1,"acceptNonAdjacent",true))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id=objectMapper.readTree(response.getContentAsString()).get("orderId").asLong();
        var before=resources();
        source.armed.set(afterCommit?"AFTER_COMMIT":"BEFORE_COMMIT");
        String outcome;
        try {
            var failed=postJson("/ticket-orders/"+id+"/pay",buyer,Map.of()).andReturn().getResponse();
            assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);outcome="HTTP "+failed.getStatus();
        } catch(jakarta.servlet.ServletException failure) { outcome=failure.getClass().getSimpleName(); }
        finally {source.armed.remove();}
        assertThat(source.killedConnection).isPositive();
        assertThat(source.uncommittedTickets).isEqualTo(1);
        assertThat(source.uncommittedStock).isEqualTo(9);
        if(afterCommit){assertThat(orders.findById(id).getStatus()).isEqualTo("PAID");}
        else {assertThat(resources()).isEqualTo(before);assertThat(orders.findById(id).getStatus()).isEqualTo("PENDING_PAYMENT");}
        postJson("/ticket-orders/"+id+"/pay",buyer,Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        var committed=resources();
        postJson("/ticket-orders/"+id+"/pay",buyer,Map.of()).andExpect(status().isOk());
        assertThat(resources()).isEqualTo(committed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='PAYMENT_SUCCEEDED' AND aggregate_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(9);
        PaymentInvariantAssertions.assertConsistent(jdbc);
        var directory=Files.createDirectories(Path.of("target","verification","day28-connection-loss"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(afterCommit?"after-commit.json":"before-commit.json").toFile(),
                Map.of("mode",afterCommit?"REAL_COMMIT_THEN_KILL_WITH_INJECTED_LOST_ACK":"REAL_KILL_BEFORE_COMMIT",
                        "connectionId",source.killedConnection,"firstResult",outcome,"uncommittedTickets",source.uncommittedTickets,
                        "uncommittedStock",source.uncommittedStock,"originalOrderId",id,"paidOrders",1,"tickets",1,"stock",9,"paymentOutboxEvents",1));
    }
    private Object resources(){return List.of("ticket_order","ticket_order_item","ticket","session_seat","session_zone","user_session_purchase","outbox_event")
            .stream().map(t->jdbc.queryForList("SELECT * FROM "+t+" ORDER BY 1")).toList();}

    static final class LossSource extends DelegatingDataSource implements AutoCloseable {
        private final IsolatedQualificationMySqlDataSource owned;
        final ThreadLocal<String> armed=new ThreadLocal<>();
        long killedConnection;int uncommittedTickets,uncommittedStock;
        LossSource(DataSourceProperties properties)throws Exception{this(new IsolatedQualificationMySqlDataSource(properties));}
        private LossSource(IsolatedQualificationMySqlDataSource owned){super(owned);this.owned=owned;}
        @Override public Connection getConnection()throws SQLException{
            var delegate=owned.getConnection();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                String mode=armed.get();
                if(method.getName().equals("commit")&&mode!=null){
                    armed.remove();
                    try(var statement=delegate.createStatement();var result=statement.executeQuery("SELECT CONNECTION_ID(),(SELECT COUNT(*) FROM ticket),(SELECT SUM(remains_amount) FROM session_zone)")){
                        result.next();killedConnection=result.getLong(1);uncommittedTickets=result.getInt(2);uncommittedStock=result.getInt(3);
                    }
                    if(mode.equals("AFTER_COMMIT"))delegate.commit();
                    try(var killer=owned.getConnection();var statement=killer.createStatement()){statement.execute("KILL CONNECTION "+killedConnection);}
                    if(mode.equals("AFTER_COMMIT"))throw new SQLException("Injected lost acknowledgement after successful database commit and real disconnect","08007");
                    delegate.commit(); // Must fail against the physically terminated MySQL connection.
                    throw new AssertionError("Killed connection unexpectedly committed");
                }
                try{return method.invoke(delegate,args);}catch(InvocationTargetException exception){throw exception.getCause();}
            });
        }
        @Override public void close(){owned.close();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Configuration {
        @Bean LossSource dataSource(DataSourceProperties properties)throws Exception{return new LossSource(properties);}
    }
}
