package com.limitactivitysystem.product.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
@Transactional(propagation = Propagation.MANDATORY)
public interface UserProductPurchaseMapper {
    @Insert("""
            INSERT INTO user_product_purchase(user_id, product_id, valid_count)
            VALUES(#{userId}, #{productId}, 0)
            ON DUPLICATE KEY UPDATE valid_count = valid_count
            """)
    int ensureExists(@Param("userId") Long userId, @Param("productId") Long productId);

    @Update("""
            UPDATE user_product_purchase
            SET valid_count = valid_count + #{quantity}
            WHERE user_id = #{userId} AND product_id = #{productId}
              AND #{quantity} > 0 AND #{purchaseLimit} > 0
              AND valid_count <= #{purchaseLimit} - #{quantity}
            """)
    int tryIncrease(@Param("userId") Long userId, @Param("productId") Long productId,
                    @Param("quantity") Integer quantity, @Param("purchaseLimit") Integer purchaseLimit);

    @Update("""
            UPDATE user_product_purchase SET valid_count=valid_count-#{quantity}
            WHERE user_id=#{userId} AND product_id=#{productId}
              AND #{quantity}>0 AND valid_count>=#{quantity}
            """)
    int tryDecrease(@Param("userId") Long userId, @Param("productId") Long productId,
                    @Param("quantity") Integer quantity);
}
