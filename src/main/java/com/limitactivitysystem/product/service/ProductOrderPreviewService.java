package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.PreviewProductOrderRequest;
import com.limitactivitysystem.product.dto.ProductOrderPreviewResponse;
import com.limitactivitysystem.product.exception.ProductException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static com.limitactivitysystem.product.exception.ProductErrorCode.INSUFFICIENT_SKU_STOCK;

@Service
@Validated
public class ProductOrderPreviewService {
    private final ProductOrderPreparationService preparation;

    public ProductOrderPreviewService(ProductOrderPreparationService preparation) {
        this.preparation = preparation;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public ProductOrderPreviewResponse preview(@NotNull @Valid PreviewProductOrderRequest request) {
        var prepared = preparation.prepare(request.getItems());
        List<ProductOrderPreviewResponse.Item> items = new ArrayList<>();
        boolean available = true;
        BigDecimal total = BigDecimal.ZERO;
        for (var item : prepared.items()) {
            var candidate = item.candidate();
            int stock = candidate.remainsStock() == null ? 0 : candidate.remainsStock();
            boolean inStock = stock >= item.request().getQuantity();
            available &= inStock;
            total = total.add(item.subtotal());
            items.add(new ProductOrderPreviewResponse.Item(candidate.skuId(), candidate.productId(),
                    candidate.productName(), candidate.skuName(), item.request().getQuantity(), stock,
                    inStock ? "AVAILABLE" : "INSUFFICIENT_STOCK", candidate.unitPrice(), item.subtotal(),
                    item.requiresPolicyAcknowledgement()));
        }
        return new ProductOrderPreviewResponse(available, total, List.copyOf(items));
    }

    public void requireStock(ProductOrderPreparationService.PreparedOrder order) {
        for (var item : order.items()) {
            Integer stock = item.candidate().remainsStock();
            if (stock == null || stock < item.request().getQuantity()) {
                throw new ProductException(INSUFFICIENT_SKU_STOCK, "Requested SKU does not have sufficient current stock");
            }
        }
    }
}
