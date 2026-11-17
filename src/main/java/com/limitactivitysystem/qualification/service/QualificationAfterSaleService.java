package com.limitactivitysystem.qualification.service;

import com.limitactivitysystem.product.dto.CreateProductAfterSaleRequest;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.model.Product;
import com.limitactivitysystem.product.model.ProductOrder;
import com.limitactivitysystem.product.model.ProductOrderItem;
import com.limitactivitysystem.product.service.ProductAfterSaleService;
import com.limitactivitysystem.qualification.mapper.QualificationAfterSaleTaskMapper;
import com.limitactivitysystem.qualification.model.TicketQualificationAfterSaleTask;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class QualificationAfterSaleService {
    private final QualificationAfterSaleTaskMapper tasks;
    private final ProductOrderMapper orders;
    private final ProductMapper products;
    private final QualificationGuardService guards;
    private final ProductAfterSaleService afterSales;
    private final DatabaseTimeMapper databaseTime;
    private final QualificationTelemetry telemetry;
    private final io.opentelemetry.api.trace.Tracer tracer;

    public QualificationAfterSaleService(QualificationAfterSaleTaskMapper tasks, ProductOrderMapper orders,
            ProductMapper products, QualificationGuardService guards, ProductAfterSaleService afterSales,
            DatabaseTimeMapper databaseTime, QualificationTelemetry telemetry,
            io.opentelemetry.api.OpenTelemetry openTelemetry) {
        this.tasks = tasks;
        this.orders = orders;
        this.products = products;
        this.guards = guards;
        this.afterSales = afterSales;
        this.databaseTime = databaseTime;
        this.telemetry = telemetry;
        this.tracer = openTelemetry.getTracer("com.limitactivitysystem.qualification");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void processTask(Long taskId) {
        var span = tracer.spanBuilder("qualification.after_sale").startSpan();
        span.setAttribute("qualification.task.id", taskId);
        try (var scope = span.makeCurrent();
             var logs = new com.limitactivitysystem.observability.TraceLogContext(span, "qualification.after_sale")) {
            processTaskInTransaction(taskId);
        } catch (RuntimeException failure) {
            span.setAttribute("error.type", failure.getClass().getSimpleName());
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }

    private void processTaskInTransaction(Long taskId) {
        TicketQualificationAfterSaleTask discovered = tasks.findContext(taskId);
        if (discovered == null) { throw new IllegalStateException("Qualification task does not exist: " + taskId); }
        ProductOrder order = orders.findByIdForUpdate(discovered.getOrderId());
        if (order == null || !discovered.getBuyerId().equals(order.getBuyerId())) {
            throw new IllegalStateException("Qualification task product order is missing or inconsistent");
        }
        TicketQualificationAfterSaleTask task = tasks.findByIdForUpdate(taskId);
        if (task == null || "SUCCEEDED".equals(task.getStatus()) || "SKIPPED".equals(task.getStatus())) { return; }
        var now = databaseTime.currentTime();
        if ("FAILED".equals(task.getStatus()) && task.getAttemptCount() < 5
                && task.getNextAttemptAt() != null && !task.getNextAttemptAt().isAfter(now)) {
            telemetry.afterSaleRetry(task.getSourceEventId(), task.getTaskId(),
                    task.getProductOrderItemId(), task.getFailureReason());
        }
        if ("FAILED".equals(task.getStatus()) && (task.getNextAttemptAt() == null
                || task.getNextAttemptAt().isAfter(now) || task.getAttemptCount() >= 5)) {
            return;
        }
        if (!List.of("PAID", "PARTIALLY_REFUNDED").contains(order.getStatus())) {
            throw new IllegalStateException("Qualification task order is no longer paid");
        }

        List<ProductOrderItem> lines = orders.findItemsForUpdate(order.getOrderId());
        ProductOrderItem item = lines.stream()
                .filter(value -> task.getProductOrderItemId().equals(value.getOrderItemId())).findFirst().orElse(null);
        if (item == null || !"TICKET_REQUIRED".equals(item.getQualificationTypeSnapshot())) {
            throw new IllegalStateException("Qualification task no longer refers to a ticket-qualified item");
        }
        if (!task.getQualificationScope().equals(item.getQualificationScopeSnapshot())) {
            throw new IllegalStateException("Qualification task scope differs from its order item snapshot");
        }

        Product product = products.share(item.getProductId());
        if (product == null || !"TICKET_REQUIRED".equals(product.qualificationType())
                || !item.getActivityIdSnapshot().equals(product.activityId())
                || !task.getQualificationScope().equals(product.qualificationScope())
                || !java.util.Objects.equals(item.getQualificationSessionIdSnapshot(), product.qualificationSessionId())) {
            throw new IllegalStateException("Current product qualification differs from the order item snapshot");
        }

        guards.lockActivities(order.getBuyerId(), List.of(item.getActivityIdSnapshot()));
        Long qualificationSessionId = "SESSION".equals(task.getQualificationScope())
                ? item.getQualificationSessionIdSnapshot() : null;
        if (products.validTickets(order.getBuyerId(), item.getActivityIdSnapshot(), qualificationSessionId) > 0) {
            changed(tasks.markSkipped(taskId), "Qualification task could not record restored ticket eligibility");
            telemetry.eventSkipped(task.getSourceEventId(), item.getOrderItemId(), "ELIGIBILITY_RESTORED_BEFORE_PROCESSING");
            return;
        }

        int refundableQuantity = item.getQuantity() - item.getRefundedQuantity() - item.getRefundPendingQuantity();
        if (refundableQuantity <= 0) {
            if (item.getRefundedQuantity() >= item.getQuantity()) {
                changed(tasks.markSucceeded(taskId), "Completed after-sale task could not be recorded");
                telemetry.afterSaleSucceeded(task.getSourceEventId(), task.getTaskId(), item.getOrderItemId());
            } else {
                changed(tasks.deferPending(taskId), "Qualification task could not wait for the existing refund reservation");
            }
            return;
        }
        if ("SHIPPED".equals(item.getFulfillmentStatus())) {
            changed(tasks.markWaitingReturn(taskId), "Shipped item could not enter the return queue");
            return;
        }
        if (!List.of("UNSHIPPED", "RECEIVED").contains(item.getFulfillmentStatus())) {
            throw new IllegalStateException("Qualification after-sale does not support the current fulfillment state");
        }

        changed(tasks.markProcessing(taskId), "Qualification after-sale task could not enter processing");
        String requestId = "ticket-qualification-" + item.getOrderItemId();
        var request = new CreateProductAfterSaleRequest(requestId, order.getOrderId(), List.of(
                new CreateProductAfterSaleRequest.Item(item.getOrderItemId(), refundableQuantity,
                        "TICKET_QUALIFICATION_LOST", true, true)));
        afterSales.applyForQualification(request, order.getBuyerId());
        ProductOrderItem updatedItem = orders.findItemsForUpdate(order.getOrderId()).stream()
                .filter(value -> task.getProductOrderItemId().equals(value.getOrderItemId())).findFirst().orElse(null);
        if (updatedItem == null) { throw new IllegalStateException("Qualification task item disappeared during after-sale"); }
        if (updatedItem.getRefundedQuantity() >= updatedItem.getQuantity()) {
            changed(tasks.markSucceeded(taskId), "Qualification after-sale task could not complete");
            telemetry.afterSaleSucceeded(task.getSourceEventId(), task.getTaskId(), item.getOrderItemId());
        } else if (updatedItem.getRefundPendingQuantity() > 0) {
            changed(tasks.deferPending(taskId), "Qualification task could not wait for the remaining refund reservation");
        } else {
            throw new IllegalStateException("Qualification after-sale completed without refunding the full order item");
        }
    }

    private void changed(int rows, String message) {
        if (rows != 1) { throw new IllegalStateException(message); }
    }
}
