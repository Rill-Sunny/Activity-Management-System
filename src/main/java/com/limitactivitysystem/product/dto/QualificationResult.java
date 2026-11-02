package com.limitactivitysystem.product.dto;

/** A current preview, never a token authorizing a later purchase. */
public record QualificationResult(Long userId,Long productId,boolean allowed,String reason) { }
