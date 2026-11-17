package com.limitactivitysystem.observability;

import com.limitactivitysystem.product.exception.ProductErrorCode;
import com.limitactivitysystem.product.exception.ProductException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessTelemetryTest {
    public void consume(Message message) { }
    public void pay(Long id) { }
    public void process(Long id) { }

    @Test void consumerExtractsDurableParentAndClearsThreadContext() throws Throwable {
        try (var harness = new Harness()) {
            var props = new MessageProperties();
            props.setHeader("traceparent", "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01");
            props.setHeader("x-event-id", UUID.randomUUID().toString());
            var invocation = invocation("consume", Message.class, new Message(new byte[0], props));
            when(invocation.proceed()).thenAnswer(call -> {
                BusinessTelemetry.consumerFailure(true, new IllegalStateException("password=never-export"));
                return null;
            });
            MDC.put("requestId", "caller-context");
            try {
                harness.telemetry.invoke("mq.consume", invocation);
                assertThat(MDC.get("requestId")).isEqualTo("caller-context");
                assertThat(MDC.get("eventId")).isNull();
                assertThat(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid()).isFalse();
                var span = harness.spans.getFirst();
                assertThat(span.getTraceId()).isEqualTo("1234567890abcdef1234567890abcdef");
                assertThat(span.getParentSpanId()).isEqualTo("1234567890abcdef");
                assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                assertThat(span.getAttributes().toString()).doesNotContain("never-export");
                assertThat(harness.meters.get("mq.consume").tags("result", "RETRY", "consumer", "consume").timer().count()).isOne();
            } finally { MDC.clear(); }
        }
    }

    static java.util.stream.Stream<RuntimeException> stockRejections() {
        return java.util.stream.Stream.of(
                new ProductException(ProductErrorCode.INSUFFICIENT_SKU_STOCK, "JWT_SECRET=never-export"),
                new com.limitactivitysystem.product.exception.ProductOrderOutOfStockException(922337203685477L,
                        List.of(new com.limitactivitysystem.product.exception.ProductOrderOutOfStockException.UnavailableItem(2L, 1, 0, "OUT_OF_STOCK"))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("stockRejections")
    void stockRejectionKeepsNonErrorSpanAndBoundedMetricLabels(RuntimeException failure) throws Throwable {
        try (var harness = new Harness()) {
            var invocation = invocation("pay", Long.class, 922337203685477L);
            when(invocation.proceed()).thenThrow(failure);
            assertThatThrownBy(() -> harness.telemetry.invoke("product.payment", invocation)).isSameAs(failure);
            var span = harness.spans.getFirst();
            assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.UNSET);
            assertThat(span.getAttributes().toString()).doesNotContain("never-export");
            var timer = harness.meters.get("product.payment").tag("result", "INSUFFICIENT_STOCK").timer();
            assertThat(timer.count()).isOne();
            assertThat(timer.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey).containsExactly("result");
        }
    }

    @Test void commitFailureIsTechnicalAndRefundPolicyIsBounded() throws Throwable {
        try (var harness = new Harness()) {
            var invocation = invocation("process", Long.class, 1L);
            when(invocation.proceed()).thenAnswer(call -> {
                BusinessTelemetry.refundPolicy("SESSION_RESCHEDULE_REFUND");
                throw new org.springframework.transaction.TransactionSystemException("commit failed: password=secret");
            });
            assertThatThrownBy(() -> harness.telemetry.invoke("ticket.refund", invocation))
                    .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
            assertThat(harness.meters.get("ticket.refund").tags("policy", "RESCHEDULE", "result", "TECHNICAL_FAILURE").timer().count()).isOne();
            assertThat(harness.spans.getFirst().getEvents()).isEmpty();
            assertThat(MDC.get("refundId")).isNull();
        }
    }

    @Test void malformedRemoteContextDoesNotAdoptUnrelatedListenerSpan() throws Throwable {
        try (var harness = new Harness()) {
            var props = new MessageProperties(); props.setHeader("traceparent", "untrusted-invalid");
            var invocation = invocation("consume", Message.class, new Message(new byte[0], props));
            var unrelated = harness.otel.getTracer("test").spanBuilder("unrelated").startSpan();
            try (var scope = unrelated.makeCurrent()) { harness.telemetry.invoke("mq.consume", invocation); }
            finally { unrelated.end(); }
            assertThat(harness.spans.getFirst().getTraceId()).isNotEqualTo(unrelated.getSpanContext().getTraceId());
        }
    }

    @Test void scrapingGaugesDoesNotConnectToDatabaseAndFailedRefreshDoesNotReportZero() throws Exception {
        var source = mock(DataSource.class);
        var meters = new SimpleMeterRegistry();
        try {
            var workload = new WorkloadMetrics(source, meters);
            assertThat(meters.get("outbox.pending").gauge().value()).isNaN();
            assertThat(meters.get("outbox.oldest.age").gauge().value()).isNaN();
            verifyNoInteractions(source);
            when(source.getConnection()).thenThrow(new SQLException("unavailable"));
            workload.refresh();
            assertThat(meters.get("outbox.pending").gauge().value()).isNaN();
            assertThat(meters.get("observability.workload.refresh.failure").counter().count()).isOne();
        } finally { meters.close(); }
    }

    @Test void scheduledRecoveryLinksSourceWithoutReplacingItsActualParent() throws Throwable {
        try (var harness = new Harness()) {
            var source = SpanContext.createFromRemoteParent("1234567890abcdef1234567890abcdef", "1234567890abcdef",
                    io.opentelemetry.api.trace.TraceFlags.getSampled(), io.opentelemetry.api.trace.TraceState.getDefault());
            when(harness.links.find(eq("qualification.task.process"), any())).thenReturn(source);
            var scheduler = harness.otel.getTracer("test").spanBuilder("scheduler").startSpan();
            try (var scope = scheduler.makeCurrent()) {
                harness.telemetry.invoke("qualification.task.process", invocation("process", Long.class, 1L));
            } finally { scheduler.end(); }
            var recovery = harness.spans.getFirst();
            assertThat(recovery.getParentSpanId()).isEqualTo(scheduler.getSpanContext().getSpanId());
            assertThat(recovery.getTraceId()).isNotEqualTo(source.getTraceId());
            assertThat(recovery.getLinks()).hasSize(1);
            assertThat(recovery.getLinks().getFirst().getSpanContext()).isEqualTo(source);
        }
    }

    private MethodInvocation invocation(String method, Class<?> argumentType, Object argument) throws Exception {
        var invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(BusinessTelemetryTest.class.getMethod(method, argumentType));
        when(invocation.getArguments()).thenReturn(new Object[]{argument});
        return invocation;
    }
    private static final class Harness implements AutoCloseable {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final List<SpanData> spans = new ArrayList<>();
        final SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> items) { spans.addAll(items); return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        })).build();
        final OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        final TaskTraceLinks links = mock(TaskTraceLinks.class);
        final BusinessTelemetry telemetry = new BusinessTelemetry(meters, otel, links);
        Harness() { when(links.find(anyString(), any())).thenReturn(SpanContext.getInvalid()); }
        public void close() { otel.close(); meters.close(); }
    }
}
