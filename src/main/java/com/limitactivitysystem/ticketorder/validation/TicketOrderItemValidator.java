package com.limitactivitysystem.ticketorder.validation;

import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderItemRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TicketOrderItemValidator implements ConstraintValidator<ValidTicketOrderItem, CreateTicketOrderItemRequest> {

    @Override
    public boolean isValid(CreateTicketOrderItemRequest item, ConstraintValidatorContext context) {
        if (item == null || item.getAllocationMode() == null
                || item.getQuantity() == null || item.getAcceptNonAdjacent() == null) {
            return true; // Required-field constraints report these separately.
        }
        return switch (item.getAllocationMode()) {
            case SELECT_SEAT -> item.getRequestedSeatId() != null
                    && item.getQuantity() == 1 && !item.getAcceptNonAdjacent();
            case AUTO_ASSIGN -> item.getRequestedSeatId() == null;
        };
    }
}
