package com.limitactivitysystem.product.mapper;

import com.limitactivitysystem.product.model.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
@Transactional(propagation = Propagation.MANDATORY)
public interface SkuMapper {
    @Select("""
            SELECT sku_id, product_id, sku_name AS name, price, total_stock AS totalStock,
                   remains_stock AS remainsStock, status, created_at AS createdAt, updated_at AS updatedAt
            FROM sku WHERE sku_id=#{skuId} FOR UPDATE
            """)
    @ConstructorArgs(value = {
            @Arg(column = "sku_id", javaType = Long.class),
            @Arg(column = "product_id", javaType = Long.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "price", javaType = java.math.BigDecimal.class),
            @Arg(column = "totalStock", javaType = Integer.class),
            @Arg(column = "remainsStock", javaType = Integer.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "createdAt", javaType = java.time.LocalDateTime.class),
            @Arg(column = "updatedAt", javaType = java.time.LocalDateTime.class)
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Sku findByIdForUpdate(@Param("skuId") Long skuId);

    @Update("""
            UPDATE sku
            SET remains_stock = remains_stock - #{quantity}
            WHERE sku_id = #{skuId} AND product_id = #{productId}
              AND status = 'ACTIVE' AND #{quantity} > 0 AND remains_stock >= #{quantity}
            """)
    int decreaseStock(@Param("skuId") Long skuId, @Param("productId") Long productId,
                      @Param("quantity") Integer quantity);
}
