package com.limitactivitysystem.refund.model;

import com.limitactivitysystem.refund.exception.TicketRefundErrorCode;
import java.math.BigDecimal;

/** Denied decisions have no monetary quote; rejectionReason is present only on denial. */
public record TicketRefundDecision(boolean allowed, BigDecimal feeRate, BigDecimal refundRate,
                                   BigDecimal feeAmount, BigDecimal refundAmount,
                                   TicketRefundErrorCode rejectionReason) { }
