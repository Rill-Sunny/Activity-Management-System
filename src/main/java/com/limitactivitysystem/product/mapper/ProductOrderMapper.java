package com.limitactivitysystem.product.mapper;

import com.limitactivitysystem.product.model.ProductOrder;
import com.limitactivitysystem.product.model.ProductOrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Mapper
@Transactional(propagation = Propagation.MANDATORY)
public interface ProductOrderMapper {
    @Insert("""
            INSERT INTO product_order(buyer_id,publisher_id,request_id,total_amount)
            VALUES(#{buyerId},#{publisherId},#{requestId},#{totalAmount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "orderId", keyColumn = "order_id")
    int insert(ProductOrder order);

    @Insert("""
            <script>
            INSERT INTO product_order_item(
                order_id,product_id,sku_id,quantity,unit_price,product_name_snapshot,sku_name_snapshot,
                activity_id_snapshot,qualification_type_snapshot,qualification_scope_snapshot,
                qualification_session_id_snapshot,refund_policy_template_snapshot,refund_policy_version_snapshot,
                special_policy_acknowledged,policy_acknowledged_at
            ) VALUES
            <foreach collection='items' item='item' separator=','>
                (#{item.orderId},#{item.productId},#{item.skuId},#{item.quantity},#{item.unitPrice},
                 #{item.productNameSnapshot},#{item.skuNameSnapshot},#{item.activityIdSnapshot},
                 #{item.qualificationTypeSnapshot},#{item.qualificationScopeSnapshot},
                 #{item.qualificationSessionIdSnapshot},#{item.refundPolicyTemplateSnapshot},
                 #{item.refundPolicyVersionSnapshot},#{item.specialPolicyAcknowledged},
                 CASE WHEN #{item.specialPolicyAcknowledged} THEN CURRENT_TIMESTAMP(3) ELSE NULL END)
            </foreach>
            </script>
            """)
    int insertItems(@Param("items") List<ProductOrderItem> items);

    @Select("""
            SELECT order_id,buyer_id,publisher_id,request_id,total_amount,status,created_at,expires_at,paid_at,closed_reason
            FROM product_order WHERE order_id=#{orderId}
            """)
    @Results(id = "productOrderResult", value = {
            @Result(column = "order_id", property = "orderId", id = true),
            @Result(column = "buyer_id", property = "buyerId"),
            @Result(column = "publisher_id", property = "publisherId"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "total_amount", property = "totalAmount"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "paid_at", property = "paidAt"),
            @Result(column = "closed_reason", property = "closedReason")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ProductOrder findById(@Param("orderId") Long orderId);

    @Select("""
            SELECT order_id,buyer_id,publisher_id,request_id,total_amount,status,created_at,expires_at,paid_at,closed_reason
            FROM product_order WHERE order_id=#{orderId} FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("productOrderResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ProductOrder findByIdForUpdate(@Param("orderId") Long orderId);

    @Select("""
            SELECT order_id,buyer_id,publisher_id,request_id,total_amount,status,created_at,expires_at,paid_at,closed_reason
            FROM product_order WHERE buyer_id=#{buyerId} AND request_id=#{requestId}
            """)
    @org.apache.ibatis.annotations.ResultMap("productOrderResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ProductOrder findByBuyerAndRequestId(@Param("buyerId") Long buyerId, @Param("requestId") String requestId);

    @Select("""
            SELECT order_item_id,order_id,product_id,sku_id,quantity,unit_price,
                   product_name_snapshot,sku_name_snapshot,activity_id_snapshot,
                   qualification_type_snapshot,qualification_scope_snapshot,qualification_session_id_snapshot,
                   qualification_guard_version_snapshot,
                   refund_policy_template_snapshot,refund_policy_version_snapshot,
                   special_policy_acknowledged,policy_acknowledged_at
            FROM product_order_item WHERE order_id=#{orderId} ORDER BY sku_id
            """)
    @Results(id = "productOrderItemResult", value = {
            @Result(column = "order_item_id", property = "orderItemId", id = true),
            @Result(column = "order_id", property = "orderId"),
            @Result(column = "product_id", property = "productId"),
            @Result(column = "sku_id", property = "skuId"),
            @Result(column = "quantity", property = "quantity"),
            @Result(column = "unit_price", property = "unitPrice"),
            @Result(column = "product_name_snapshot", property = "productNameSnapshot"),
            @Result(column = "sku_name_snapshot", property = "skuNameSnapshot"),
            @Result(column = "activity_id_snapshot", property = "activityIdSnapshot"),
            @Result(column = "qualification_type_snapshot", property = "qualificationTypeSnapshot"),
            @Result(column = "qualification_scope_snapshot", property = "qualificationScopeSnapshot"),
            @Result(column = "qualification_session_id_snapshot", property = "qualificationSessionIdSnapshot"),
            @Result(column = "qualification_guard_version_snapshot", property = "qualificationGuardVersionSnapshot"),
            @Result(column = "refund_policy_template_snapshot", property = "refundPolicyTemplateSnapshot"),
            @Result(column = "refund_policy_version_snapshot", property = "refundPolicyVersionSnapshot"),
            @Result(column = "special_policy_acknowledged", property = "specialPolicyAcknowledged"),
            @Result(column = "policy_acknowledged_at", property = "policyAcknowledgedAt")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<ProductOrderItem> findItems(@Param("orderId") Long orderId);

    @Update("""
            UPDATE product_order_item
            SET qualification_guard_version_snapshot=#{version}
            WHERE order_id=#{orderId} AND order_item_id=#{orderItemId}
              AND qualification_type_snapshot='TICKET_REQUIRED'
              AND qualification_guard_version_snapshot IS NULL
            """)
    int saveQualificationGuardVersion(@Param("orderId") Long orderId,
                                      @Param("orderItemId") Long orderItemId,
                                      @Param("version") Long version);

    @Update("""
            UPDATE product_order
            SET status='PAID', paid_at=CURRENT_TIMESTAMP(3)
            WHERE order_id=#{orderId} AND status='PENDING_PAYMENT'
              AND created_at <= CURRENT_TIMESTAMP(3) AND expires_at > CURRENT_TIMESTAMP(3)
            """)
    int markPaidIfPendingAndUnexpired(@Param("orderId") Long orderId);

    @Update("""
            UPDATE product_order
            SET status='CLOSED', closed_reason='OUT_OF_STOCK'
            WHERE order_id=#{orderId} AND status='PENDING_PAYMENT'
            """)
    int closePendingForOutOfStock(@Param("orderId") Long orderId);

    @Update("""
            UPDATE product_order
            SET status='CLOSED', closed_reason='EXPIRED'
            WHERE order_id=#{orderId} AND status='PENDING_PAYMENT'
              AND expires_at <= CURRENT_TIMESTAMP(3)
            """)
    int closePendingIfExpired(@Param("orderId") Long orderId);

    @Select("""
            SELECT COALESCE((SELECT valid_count FROM user_product_purchase
                             WHERE user_id=#{userId} AND product_id=#{productId}),0)
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Integer findValidCount(@Param("userId") Long userId, @Param("productId") Long productId);
}
