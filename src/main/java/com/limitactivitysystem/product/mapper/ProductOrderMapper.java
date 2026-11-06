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
                   special_policy_acknowledged,policy_acknowledged_at,
                   fulfillment_status,shipped_quantity,received_quantity,shipped_at,received_at,
                   refund_pending_quantity,refunded_quantity,returned_quantity,restocked_quantity
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
            @Result(column = "policy_acknowledged_at", property = "policyAcknowledgedAt"),
            @Result(column = "fulfillment_status", property = "fulfillmentStatus"),
            @Result(column = "shipped_quantity", property = "shippedQuantity"),
            @Result(column = "received_quantity", property = "receivedQuantity"),
            @Result(column = "shipped_at", property = "shippedAt"),
            @Result(column = "received_at", property = "receivedAt"),
            @Result(column = "refund_pending_quantity", property = "refundPendingQuantity"),
            @Result(column = "refunded_quantity", property = "refundedQuantity"),
            @Result(column = "returned_quantity", property = "returnedQuantity"),
            @Result(column = "restocked_quantity", property = "restockedQuantity")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<ProductOrderItem> findItems(@Param("orderId") Long orderId);

    @Select("""
            SELECT order_item_id,order_id,product_id,sku_id,quantity,unit_price,
                   product_name_snapshot,sku_name_snapshot,activity_id_snapshot,
                   qualification_type_snapshot,qualification_scope_snapshot,qualification_session_id_snapshot,
                   qualification_guard_version_snapshot,refund_policy_template_snapshot,refund_policy_version_snapshot,
                   special_policy_acknowledged,policy_acknowledged_at,
                   fulfillment_status,shipped_quantity,received_quantity,shipped_at,received_at,
                   refund_pending_quantity,refunded_quantity,returned_quantity,restocked_quantity
            FROM product_order_item WHERE order_id=#{orderId} ORDER BY order_item_id FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("productOrderItemResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<ProductOrderItem> findItemsForUpdate(@Param("orderId") Long orderId);

    @Update("""
            UPDATE product_order_item
            SET shipped_quantity=#{quantity},fulfillment_status='SHIPPED',shipped_at=CURRENT_TIMESTAMP(3)
            WHERE order_id=#{orderId} AND order_item_id=#{orderItemId}
              AND fulfillment_status='UNSHIPPED' AND shipped_quantity=0 AND received_quantity=0
              AND refund_pending_quantity=0 AND #{quantity}>0
              AND quantity-refunded_quantity=#{quantity}
            """)
    int markShipped(@Param("orderId") Long orderId, @Param("orderItemId") Long orderItemId,
                    @Param("quantity") Integer quantity);

    @Update("""
            UPDATE product_order_item
            SET received_quantity=shipped_quantity,fulfillment_status='RECEIVED',received_at=CURRENT_TIMESTAMP(3)
            WHERE order_id=#{orderId} AND order_item_id=#{orderItemId}
              AND fulfillment_status='SHIPPED' AND received_quantity=0 AND shipped_quantity=#{quantity}
              AND #{quantity}>0
            """)
    int markReceived(@Param("orderId") Long orderId, @Param("orderItemId") Long orderItemId,
                     @Param("quantity") Integer quantity);

    @Update("""
            UPDATE product_order_item
            SET refund_pending_quantity=refund_pending_quantity+#{quantity}
            WHERE order_id=#{orderId} AND order_item_id=#{orderItemId}
              AND #{quantity}>0
              AND quantity-refunded_quantity-refund_pending_quantity>=#{quantity}
            """)
    int reserveRefundQuantity(@Param("orderId") Long orderId, @Param("orderItemId") Long orderItemId,
                              @Param("quantity") Integer quantity);

    @Update("""
            UPDATE product_order_item
            SET refund_pending_quantity=refund_pending_quantity-#{quantity},
                refunded_quantity=refunded_quantity+#{quantity},
                returned_quantity=returned_quantity+#{returnedQuantity},
                restocked_quantity=restocked_quantity+#{restockedQuantity}
            WHERE order_id=#{orderId} AND order_item_id=#{orderItemId}
              AND #{quantity}>0 AND refund_pending_quantity>=#{quantity}
              AND #{returnedQuantity}>=0 AND #{restockedQuantity}>=0
            """)
    int completeRefund(@Param("orderId") Long orderId, @Param("orderItemId") Long orderItemId,
                       @Param("quantity") Integer quantity, @Param("returnedQuantity") Integer returnedQuantity,
                       @Param("restockedQuantity") Integer restockedQuantity);

    @Select("SELECT COALESCE(SUM(quantity-refunded_quantity),0) FROM product_order_item WHERE order_id=#{orderId}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Integer findUnrefundedQuantity(@Param("orderId") Long orderId);

    @Update("""
            UPDATE product_order SET status=#{status}
            WHERE order_id=#{orderId} AND status IN ('PAID','PARTIALLY_REFUNDED')
            """)
    int updateRefundSummary(@Param("orderId") Long orderId, @Param("status") String status);

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
