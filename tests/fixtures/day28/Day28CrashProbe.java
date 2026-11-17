import com.limitactivitysystem.LimitActivitySystemApplication;
import com.limitactivitysystem.cancellation.*;
import com.limitactivitysystem.messaging.*;
import com.limitactivitysystem.outbox.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** A separate JVM is halted without shutdown hooks at an already committed recovery boundary. */
public class Day28CrashProbe {
    public static void main(String[] args)throws Exception{
        if(!"true".equals(System.getenv("RUN_DAY28_FAULTS")))throw new IllegalArgumentException("Explicit isolated fault opt-in required");
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/day28_validation\\?.*"))throw new IllegalArgumentException("Owned Day 28 database required");
        var app=new SpringApplication(LimitActivitySystemApplication.class);
        var context=app.run("--server.port=0","--management.server.port=0","--spring.profiles.active=day28-probe",
                "--spring.flyway.enabled=false","--spring.rabbitmq.listener.simple.auto-startup=false",
                "--outbox.relay.initial-delay-ms=3600000","--outbox.relay.scan-delay-ms=3600000",
                "--ticket-order.expiration.enabled=false","--product-order.expiration.enabled=false",
                "--cancellation.worker.enabled=false","--management.tracing.sampling.probability=1");
        var jdbc=context.getBean(JdbcTemplate.class);
        if(!"day28_validation".equals(jdbc.queryForObject("SELECT DATABASE()",String.class)))throw new IllegalStateException("Wrong database");
        var evidence=new LinkedHashMap<String,Object>();evidence.put("mode",args[0]);evidence.put("pid",ProcessHandle.current().pid());
        if(args[0].equals("F01")||args[0].equals("F02")){
            String target=System.getenv("DAY28_EVENT_ID");
            var claims=context.getBean(OutboxClaimService.class);
            var events=claims.claimBatch(50,8,60);
            var event=events.stream().filter(e->e.getEventId().equals(target)).findFirst().orElseThrow();
            var publisher=context.getBean(OutboxPublisher.class);
            // Publish other committed events normally, leaving only the named boundary interrupted.
            for(var other:events)if(!other.getEventId().equals(target)){publisher.publish(other);claims.markSent(other);}
            publisher.publish(event);evidence.put("eventId",target);evidence.put("attempt",event.getAttempts());evidence.put("brokerConfirmed",true);
            if(args[0].equals("F02")){
                claims.markSent(event);
                var connection=context.getBean(ConnectionFactory.class).createConnection();
                var channel=connection.createChannel(false);
                com.rabbitmq.client.GetResponse delivery=null;
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while(delivery==null&&System.nanoTime()<until){delivery=channel.basicGet(RabbitMqTopology.PAYMENT_NOTIFICATION_QUEUE,false);Thread.sleep(20);}
                if(delivery==null)throw new IllegalStateException("No delivery");
                String received=String.valueOf(delivery.getProps().getHeaders().get("x-event-id"));
                if(!target.equals(received))throw new IllegalStateException("Unexpected message");
                var properties=new org.springframework.amqp.core.MessageProperties();
                delivery.getProps().getHeaders().forEach(properties::setHeader);
                properties.setMessageId(delivery.getProps().getMessageId());
                properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
                var message=new org.springframework.amqp.core.Message(delivery.getBody(),properties);
                var boundary=(com.rabbitmq.client.Channel)java.lang.reflect.Proxy.newProxyInstance(
                        com.rabbitmq.client.Channel.class.getClassLoader(),new Class<?>[]{com.rabbitmq.client.Channel.class},
                        (proxy,method,arguments)->{
                            if(method.getName().equals("basicAck")){
                                if(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,target)!=1
                                        ||jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE event_id=?",Integer.class,target)!=1)
                                    throw new IllegalStateException("Effect and consumer marker did not commit together");
                                evidence.put("businessCommitted",true);evidence.put("acked",false);
                                evidence.put("consumerTraceId",io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId());
                                halt(evidence);
                                return null;
                            }
                            try{return method.invoke(channel,arguments);}
                            catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        });
                // Invoke the actual advised consumer. Its real service transaction commits before basicAck.
                context.getBean(AsyncEventConsumer.class).consumePaymentNotification(message,boundary);
                throw new IllegalStateException("Consumer did not reach the committed ACK boundary");
            }
        }else if(args[0].equals("F07")){
            int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
            try(var client=java.net.http.HttpClient.newHttpClient()){
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/publisher/sessions/270101/cancel"))
                        .header("Authorization","Bearer "+System.getenv("DAY28_PUBLISHER_TOKEN")).header("Content-Type","application/json")
                        .header("traceparent","00-"+UUID.randomUUID().toString().replace("-","")+"-1234567890abcdef-01")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"requestId\":\"day28-f07\",\"reason\":\"Crash after task claim\"}")).build();
                if(client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()!=202)throw new IllegalStateException("Cancellation API failed");
            }
            var mapper=context.getBean(CancellationMapper.class);var batches=context.getBean(CancellationBatchService.class);
            long batch=jdbc.queryForObject("SELECT batch_id FROM cancellation_batch ORDER BY batch_id LIMIT 1",Long.class);
            while(mapper.batchById(batch).scanPhase()<4)batches.generate(batch);
            CancellationMapper.Item item;
            do{
                item=batches.claim();if(item==null)throw new IllegalStateException("No cancellation task");
                if(!item.targetType().equals("TICKET"))context.getBean(CancellationItemProcessor.class).process(item);
            }while(!item.targetType().equals("TICKET"));
            evidence.put("itemId",item.itemId());evidence.put("targetId",item.targetId());evidence.put("claimToken",item.claimToken());evidence.put("leaseUntil",item.leaseUntil().toString());
        }else throw new IllegalArgumentException("Supported: F01/F02/F07");
        halt(evidence);
    }
    private static void halt(Map<String,Object> evidence)throws Exception{
        evidence.put("boundaryAt",java.time.Instant.now().toString());
        Path output=Path.of(System.getenv("DAY28_CRASH_EVIDENCE"));Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),evidence);
        Runtime.getRuntime().halt(77); // Intentionally no context.close(), ACK, finally, or shutdown hook.
    }
}
