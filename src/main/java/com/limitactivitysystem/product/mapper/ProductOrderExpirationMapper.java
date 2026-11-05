package com.limitactivitysystem.product.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Bounded database-time scan; each status transition runs in its own transaction. */
@Mapper
public interface ProductOrderExpirationMapper {
    @Select("""
            SELECT order_id FROM product_order
            WHERE status='PENDING_PAYMENT' AND expires_at <= CURRENT_TIMESTAMP(3)
            ORDER BY expires_at, order_id LIMIT #{limit}
            """)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    List<Long> findExpiredPendingOrderIds(@Param("limit") int limit);
}
