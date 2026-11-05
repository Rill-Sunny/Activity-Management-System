package com.limitactivitysystem.product.schedule;

import com.limitactivitysystem.product.mapper.ProductOrderExpirationMapper;
import com.limitactivitysystem.product.service.ProductOrderClosureService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "product-order.expiration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductOrderExpirationConfiguration {
    @Bean
    ProductOrderExpirationJob productOrderExpirationJob(ProductOrderExpirationMapper orders, ProductOrderClosureService closing,
            @Value("${product-order.expiration.batch-size:100}") int batchSize) {
        return new ProductOrderExpirationJob(orders, closing, batchSize);
    }
}
