package com.limitactivitysystem.product.mapper;

import com.limitactivitysystem.product.model.ProductAfterSaleItem;
import com.limitactivitysystem.product.model.ProductAfterSaleRequest;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Mapper
@Transactional(propagation = Propagation.MANDATORY)
public interface ProductAfterSaleMapper {
    @Insert("""
            INSERT INTO product_after_sale_request(order_id,buyer_id,request_id,reason)
            VALUES(#{orderId},#{buyerId},#{requestId},#{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "afterSaleRequestId", keyColumn = "after_sale_request_id")
    int insertRequest(ProductAfterSaleRequest request);

    @Insert("""
            <script>
            INSERT INTO product_after_sale_item(
                after_sale_request_id,order_item_id,sku_id,reason_code,requested_quantity,simulate_inspection_passed,simulate_resalable,
                status,inspection_result,is_resalable
            ) VALUES
            <foreach collection='items' item='item' separator=','>
                (#{item.afterSaleRequestId},#{item.orderItemId},#{item.skuId},#{item.reasonCode},#{item.requestedQuantity},
                 #{item.simulateInspectionPassed},#{item.simulateResalable},#{item.status},#{item.inspectionResult},#{item.isResalable})
            </foreach>
            </script>
            """)
    int insertItems(@Param("items") List<ProductAfterSaleItem> items);

    @Select("""
            SELECT after_sale_request_id,order_id,buyer_id,request_id,reason,requested_at,status,failure_reason
            FROM product_after_sale_request WHERE buyer_id=#{buyerId} AND request_id=#{requestId}
            """)
    @Results(id = "productAfterSaleRequestResult", value = {
            @Result(column = "after_sale_request_id", property = "afterSaleRequestId", id = true),
            @Result(column = "order_id", property = "orderId"),
            @Result(column = "buyer_id", property = "buyerId"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "reason", property = "reason"),
            @Result(column = "requested_at", property = "requestedAt"),
            @Result(column = "status", property = "status"),
            @Result(column = "failure_reason", property = "failureReason")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ProductAfterSaleRequest findByBuyerAndRequestId(@Param("buyerId") Long buyerId, @Param("requestId") String requestId);

    @Select("""
            SELECT after_sale_request_id,order_id,buyer_id,request_id,reason,requested_at,status,failure_reason
            FROM product_after_sale_request WHERE after_sale_request_id=#{id} FOR UPDATE
            """)
    @ResultMap("productAfterSaleRequestResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ProductAfterSaleRequest findByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT after_sale_item_id,after_sale_request_id,order_item_id,sku_id,reason_code,requested_quantity,
                   simulate_inspection_passed,simulate_resalable,status,returned_quantity,refunded_quantity,returned_at,inspection_result,is_resalable,created_at
            FROM product_after_sale_item WHERE after_sale_request_id=#{requestId}
            ORDER BY order_item_id
            """)
    @Results(id = "productAfterSaleItemResult", value = {
            @Result(column = "after_sale_item_id", property = "afterSaleItemId", id = true),
            @Result(column = "after_sale_request_id", property = "afterSaleRequestId"),
            @Result(column = "order_item_id", property = "orderItemId"),
            @Result(column = "sku_id", property = "skuId"),
            @Result(column = "reason_code", property = "reasonCode"),
            @Result(column = "requested_quantity", property = "requestedQuantity"),
            @Result(column = "simulate_inspection_passed", property = "simulateInspectionPassed"),
            @Result(column = "simulate_resalable", property = "simulateResalable"),
            @Result(column = "status", property = "status"),
            @Result(column = "returned_quantity", property = "returnedQuantity"),
            @Result(column = "refunded_quantity", property = "refundedQuantity"),
            @Result(column = "returned_at", property = "returnedAt"),
            @Result(column = "inspection_result", property = "inspectionResult"),
            @Result(column = "is_resalable", property = "isResalable"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<ProductAfterSaleItem> findItems(@Param("requestId") Long requestId);

    @Select("""
            SELECT after_sale_item_id,after_sale_request_id,order_item_id,sku_id,reason_code,requested_quantity,
                   simulate_inspection_passed,simulate_resalable,status,returned_quantity,refunded_quantity,returned_at,inspection_result,is_resalable,created_at
            FROM product_after_sale_item WHERE after_sale_request_id=#{requestId}
            ORDER BY order_item_id FOR UPDATE
            """)
    @ResultMap("productAfterSaleItemResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<ProductAfterSaleItem> findItemsForUpdate(@Param("requestId") Long requestId);

    @Update("UPDATE product_after_sale_request SET status='PROCESSING' WHERE after_sale_request_id=#{id} AND status='PENDING'")
    int markProcessing(@Param("id") Long id);

    @Update("UPDATE product_after_sale_item SET status='PROCESSING' WHERE after_sale_request_id=#{id} AND status='PENDING'")
    int markItemsProcessing(@Param("id") Long id);

    @Update("UPDATE product_after_sale_request SET status='SUCCEEDED',failure_reason=NULL WHERE after_sale_request_id=#{id} AND status='PROCESSING'")
    int markSucceeded(@Param("id") Long id);

    @Update("""
            UPDATE product_after_sale_item
            SET status='SUCCEEDED',returned_quantity=#{returnedQuantity},refunded_quantity=requested_quantity,
                returned_at=CASE WHEN #{returnedQuantity}>0 THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
                inspection_result=#{inspectionResult},is_resalable=#{isResalable}
            WHERE after_sale_item_id=#{id} AND status='PROCESSING' AND refunded_quantity=0
            """)
    int markItemSucceeded(@Param("id") Long id, @Param("returnedQuantity") Integer returnedQuantity,
                          @Param("inspectionResult") String inspectionResult, @Param("isResalable") Boolean isResalable);

    @Update("UPDATE product_after_sale_request SET status='FAILED',failure_reason=#{reason} WHERE after_sale_request_id=#{id}")
    int markFailed(@Param("id") Long id, @Param("reason") String reason);

    @Update("UPDATE product_after_sale_item SET status='FAILED' WHERE after_sale_request_id=#{id}")
    int markItemsFailed(@Param("id") Long id);

    @Update("UPDATE product_after_sale_request SET status='PENDING',failure_reason=NULL WHERE after_sale_request_id=#{id} AND status='FAILED'")
    int reopenFailed(@Param("id") Long id);

    @Update("""
            UPDATE product_after_sale_item
            SET status='PENDING',returned_quantity=0,refunded_quantity=0,returned_at=NULL,
                inspection_result=#{inspectionResult},is_resalable=NULL
            WHERE after_sale_item_id=#{id} AND status='FAILED'
            """)
    int reopenFailedItem(@Param("id") Long id, @Param("inspectionResult") String inspectionResult);
}
