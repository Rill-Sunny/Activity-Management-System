package com.limitactivitysystem.qualification.service;

import com.limitactivitysystem.messaging.ConsumerFailureMapper;
import com.limitactivitysystem.messaging.ConsumerProcessMapper;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.model.Product;
import com.limitactivitysystem.product.model.ProductOrder;
import com.limitactivitysystem.product.model.ProductOrderItem;
import com.limitactivitysystem.qualification.mapper.QualificationAfterSaleTaskMapper;
import com.limitactivitysystem.qualification.model.QualificationTaskCandidate;
import com.limitactivitysystem.qualification.model.TicketQualificationAfterSaleTask;
import com.limitactivitysystem.qualification.model.TicketQualificationChangedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketQualificationChangedHandlerService {
    public static final String CONSUMER_NAME = "ticket-qualification-after-sale-v1";

    private final ConsumerProcessMapper processed;
    private final ConsumerFailureMapper failures;
    private final QualificationAfterSaleTaskMapper tasks;
    private final ProductOrderMapper orders;
    private final ProductMapper products;
    private final QualificationGuardService guards;
    private final QualificationTelemetry telemetry;

    public TicketQualificationChangedHandlerService(ConsumerProcessMapper processed, ConsumerFailureMapper failures,
            QualificationAfterSaleTaskMapper tasks, ProductOrderMapper orders, ProductMapper products,
            QualificationGuardService guards, QualificationTelemetry telemetry) {
        this.processed = processed;
        this.failures = failures;
        this.tasks = tasks;
        this.orders = orders;
        this.products = products;
        this.guards = guards;
        this.telemetry = telemetry;
    }

    /** Commits event de-duplication and task creation before the task's separate after-sale transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<Long> registerTasks(TicketQualificationChangedEvent event) {
        validate(event);
        int firstDelivery = processed.begin(CONSUMER_NAME, event.eventId());
        failures.delete(CONSUMER_NAME, event.eventId());
        if (firstDelivery == 0) {
            telemetry.eventSkipped(event.eventId(), null, "DUPLICATE_EVENT");
            return tasks.findIdsBySourceEventId(event.eventId());
        }

        List<QualificationTaskCandidate> candidates = tasks.findCandidates(
                event.buyerId(), event.activityId(), event.sessionId());
        Map<Long, LockedOrder> lockedOrders = new LinkedHashMap<>();
        for (QualificationTaskCandidate candidate : candidates) {
            if (lockedOrders.containsKey(candidate.orderId())) { continue; }
            ProductOrder order = orders.findByIdForUpdate(candidate.orderId());
            if (order == null || !event.buyerId().equals(order.getBuyerId())
                    || !List.of("PAID", "PARTIALLY_REFUNDED").contains(order.getStatus())) {
                lockedOrders.put(candidate.orderId(), new LockedOrder(null, List.of()));
                continue;
            }
            lockedOrders.put(candidate.orderId(), new LockedOrder(order, orders.findItemsForUpdate(order.getOrderId())));
        }

        List<LockedCandidate> affected = new ArrayList<>();
        for (QualificationTaskCandidate candidate : candidates) {
            LockedOrder locked = lockedOrders.get(candidate.orderId());
            if (locked == null || locked.order() == null) { continue; }
            ProductOrderItem item = locked.items().stream()
                    .filter(value -> candidate.orderItemId().equals(value.getOrderItemId())).findFirst().orElse(null);
            if (item == null || !"TICKET_REQUIRED".equals(item.getQualificationTypeSnapshot())
                    || !event.activityId().equals(item.getActivityIdSnapshot())) { continue; }
            if (!"ACTIVITY".equals(item.getQualificationScopeSnapshot())
                    && !("SESSION".equals(item.getQualificationScopeSnapshot())
                    && event.sessionId().equals(item.getQualificationSessionIdSnapshot()))) { continue; }
            Product product = products.share(item.getProductId());
            if (product == null || !"TICKET_REQUIRED".equals(product.qualificationType())
                    || !event.activityId().equals(product.activityId())
                    || !item.getQualificationScopeSnapshot().equals(product.qualificationScope())
                    || !java.util.Objects.equals(item.getQualificationSessionIdSnapshot(), product.qualificationSessionId())) {
                continue;
            }
            affected.add(new LockedCandidate(locked.order(), item));
        }
        if (affected.isEmpty()) {
            telemetry.eventSkipped(event.eventId(), null, "NO_AFFECTED_ORDER_ITEM");
            return List.of();
        }

        // Lock all product orders in ascending order before the shared qualification guard.
        guards.lockActivities(event.buyerId(), List.of(event.activityId()));
        Map<Long, Boolean> eligibilityBySession = new HashMap<>();
        List<Long> taskIds = new ArrayList<>();
        for (LockedCandidate candidate : affected) {
            ProductOrderItem item = candidate.item();
            Long qualificationSessionId = "SESSION".equals(item.getQualificationScopeSnapshot())
                    ? item.getQualificationSessionIdSnapshot() : null;
            boolean eligible = eligibilityBySession.computeIfAbsent(qualificationSessionId,
                    sessionId -> products.validTickets(event.buyerId(), event.activityId(), sessionId) > 0);
            TicketQualificationAfterSaleTask existing = tasks.findByOrderItemForUpdate(item.getOrderItemId());
            if (existing != null) {
                if (!eligible && "SKIPPED".equals(existing.getStatus())) {
                    if (tasks.reopenSkipped(existing.getTaskId(), event.eventId()) != 1) {
                        throw new IllegalStateException("Skipped qualification task could not be reopened");
                    }
                }
                telemetry.eventSkipped(event.eventId(), item.getOrderItemId(), "TASK_ALREADY_EXISTS");
                taskIds.add(existing.getTaskId());
                continue;
            }
            if (eligible) {
                telemetry.eventSkipped(event.eventId(), item.getOrderItemId(), "ELIGIBILITY_RETAINED");
                continue;
            }

            TicketQualificationAfterSaleTask task = new TicketQualificationAfterSaleTask();
            task.setSourceEventId(event.eventId());
            task.setProductOrderItemId(item.getOrderItemId());
            task.setQualificationScope(item.getQualificationScopeSnapshot());
            task.setStatus(eligible ? "SKIPPED" : "PENDING");
            if (tasks.insert(task) != 1 || task.getTaskId() == null) {
                throw new IllegalStateException("Qualification after-sale task could not be created");
            }
            if (eligible) { telemetry.eventSkipped(event.eventId(), item.getOrderItemId(), "ELIGIBILITY_RETAINED"); }
            else {
                telemetry.afterSaleCreated(event.eventId(), task.getTaskId(), item.getOrderItemId());
                taskIds.add(task.getTaskId());
            }
        }
        return taskIds.stream().distinct().toList();
    }

    private record LockedOrder(ProductOrder order, List<ProductOrderItem> items) { }
    private record LockedCandidate(ProductOrder order, ProductOrderItem item) { }

    private void validate(TicketQualificationChangedEvent event) {
        if (event == null || event.eventId() == null || event.eventId().length() != 36
                || event.buyerId() == null || event.buyerId() <= 0
                || event.activityId() == null || event.activityId() <= 0
                || event.sessionId() == null || event.sessionId() <= 0
                || event.refundId() == null || event.refundId() <= 0
                || event.occurredAt() == null || event.eventVersion() == null || event.eventVersion() < 0) {
            throw new IllegalArgumentException("Ticket qualification change event is incomplete");
        }
    }
}
