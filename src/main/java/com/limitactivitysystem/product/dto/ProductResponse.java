package com.limitactivitysystem.product.dto;

import com.limitactivitysystem.product.model.*;
import java.time.LocalDateTime;
import java.util.List;

public record ProductResponse(Long productId, Long publisherId, Long activityId, String name, String description,
        String status, String qualificationType, String qualificationScope, Long qualificationSessionId,
        Integer purchaseLimit, String refundPolicy, Integer refundPolicyVersion, boolean requiresPolicyAcknowledgement,
        String refundPolicyNotice, LocalDateTime firstOnSaleAt, LocalDateTime createdAt, LocalDateTime updatedAt, List<Sku> skus) {
    public static ProductResponse from(Product p,List<Sku> skus) {
        boolean special="SPECIAL_NO_REASON_RETURN".equals(p.refundPolicy());
        String notice=special ? "不支持普通无理由退货；质量问题、失去持票资格及活动取消等特殊处理不因此排除。购买前须明确确认。"
                : "常规七日无理由退货，按有效签收时间和商品状态判断；质量及特殊原因另行处理。";
        return new ProductResponse(p.productId(),p.publisherId(),p.activityId(),p.name(),p.description(),p.status(),p.qualificationType(),
                p.qualificationScope(),p.qualificationSessionId(),p.purchaseLimit(),p.refundPolicy(),p.refundPolicyVersion(),special,notice,
                p.firstOnSaleAt(),p.createdAt(),p.updatedAt(),List.copyOf(skus));
    }
}
