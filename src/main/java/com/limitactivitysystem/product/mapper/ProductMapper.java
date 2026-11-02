package com.limitactivitysystem.product.mapper;

import com.limitactivitysystem.product.model.*;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Mapper
@Transactional(propagation=Propagation.MANDATORY)
public interface ProductMapper {
    String PRODUCT_COLUMNS="product_id AS productId,publisher_id AS publisherId,activity_id AS activityId,name,description,status,qualification_type AS qualificationType,qualification_scope AS qualificationScope,qualification_session_id AS qualificationSessionId,refund_policy_template AS refundPolicy,refund_policy_version AS refundPolicyVersion,purchase_limit AS purchaseLimit,first_on_sale_at AS firstOnSaleAt,created_at AS createdAt,updated_at AS updatedAt";
    String SKU_COLUMNS="sku_id AS skuId,product_id AS productId,sku_name AS name,price,total_stock AS totalStock,remains_stock AS remainsStock,status,created_at AS createdAt,updated_at AS updatedAt";

    @Select("SELECT "+PRODUCT_COLUMNS+" FROM product WHERE product_id=#{id}")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Product find(@Param("id") Long id);
    @Select("SELECT "+PRODUCT_COLUMNS+" FROM product WHERE product_id=#{id} FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Product share(@Param("id") Long id);
    @Select("SELECT "+PRODUCT_COLUMNS+" FROM product WHERE product_id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Product lock(@Param("id") Long id);
    @Select("SELECT product_id FROM product WHERE publisher_id=#{id} ORDER BY product_id")
    List<Long> ownedIds(@Param("id") Long id);
    @Select("SELECT product_id FROM product WHERE activity_id=#{id} AND status='ON_SALE' ORDER BY product_id")
    List<Long> publicIds(@Param("id") Long id);
    @Insert("""
            INSERT INTO product(publisher_id,activity_id,name,description,qualification_type,qualification_scope,
                qualification_session_id,refund_policy_template,purchase_limit)
            VALUES(#{publisherId},#{activityId},#{name},#{description},#{qualificationType},#{qualificationScope},
                #{qualificationSessionId},#{refundPolicy},#{purchaseLimit})
            """)
    @Options(useGeneratedKeys=true,keyProperty="productId",keyColumn="product_id")
    int insert(Map<String,Object> values);
    @Update("""
            UPDATE product SET status='ON_SALE',first_on_sale_at=COALESCE(first_on_sale_at,CURRENT_TIMESTAMP(3))
            WHERE product_id=#{id} AND status IN ('DRAFT','OFF_SALE')
            """)
    int publish(@Param("id") Long id);
    @Update("UPDATE product SET status='OFF_SALE' WHERE product_id=#{id} AND status='ON_SALE'")
    int takeOffSale(@Param("id") Long id);

    @Select("SELECT "+SKU_COLUMNS+" FROM sku WHERE product_id=#{id} ORDER BY sku_id")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Sku> skus(@Param("id") Long id);
    @Select("SELECT "+SKU_COLUMNS+" FROM sku WHERE product_id=#{productId} AND sku_id=#{skuId}")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Sku sku(@Param("productId") Long productId,@Param("skuId") Long skuId);
    @Insert("""
            INSERT INTO sku(product_id,sku_name,price,total_stock,remains_stock)
            SELECT product_id,#{name},#{price},#{stock},#{stock} FROM product
            WHERE product_id=#{productId} AND status='DRAFT' AND first_on_sale_at IS NULL
            """)
    @Options(useGeneratedKeys=true,keyProperty="skuId",keyColumn="sku_id")
    int insertSku(Map<String,Object> values);
    @Update("""
            UPDATE sku s JOIN product p ON p.product_id=s.product_id
            SET s.sku_name=#{name},s.price=#{price},s.total_stock=#{stock},s.remains_stock=#{stock}
            WHERE s.sku_id=#{skuId} AND s.product_id=#{productId} AND p.status='DRAFT' AND p.first_on_sale_at IS NULL
            """)
    int updateSku(Map<String,Object> values);
    @Select("SELECT COUNT(*) FROM product_whitelist_member WHERE product_id=#{productId} AND user_id=#{userId}")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    int isMember(@Param("productId") Long productId,@Param("userId") Long userId);
    @Insert("""
            INSERT INTO product_whitelist_member(product_id,user_id) VALUES(#{productId},#{userId})
            ON DUPLICATE KEY UPDATE user_id=product_whitelist_member.user_id
            """)
    int addMember(@Param("productId") Long productId,@Param("userId") Long userId);
    @Delete("DELETE FROM product_whitelist_member WHERE product_id=#{productId} AND user_id=#{userId}")
    int removeMember(@Param("productId") Long productId,@Param("userId") Long userId);
    @Select("SELECT user_id FROM product_whitelist_member WHERE product_id=#{id} ORDER BY user_id")
    List<Long> members(@Param("id") Long id);
    @Select("""
            SELECT COUNT(*) FROM ticket t
            JOIN ticket_order_item i ON i.order_item_id=t.order_item_id
            JOIN ticket_order o ON o.order_id=i.order_id
            JOIN activity_session s ON s.activity_session_id=o.session_id
            JOIN session_zone z ON z.zone_id=i.zone_id AND z.session_id=o.session_id AND z.zone_type='PAID'
            JOIN session_seat st ON st.seat_id=t.seat_id AND st.zone_id=z.zone_id AND st.allocation_status='SOLD'
            WHERE o.buyer_id=#{userId} AND s.activity_id=#{activityId}
              AND (#{sessionId} IS NULL OR o.session_id=#{sessionId})
              AND t.status='VALID' AND t.refunded_at IS NULL AND t.paid_price=i.unit_price
              AND o.status IN ('PAID','PARTIALLY_REFUNDED')
            """)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    long validTickets(@Param("userId") Long userId,@Param("activityId") Long activityId,@Param("sessionId") Long sessionId);
}
