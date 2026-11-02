package com.limitactivitysystem.product.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.*;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.product.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.*;
import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
@Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
public class ProductManagementService {
    private final ProductScopeService scope;
    private final ProductMapper products;
    private final AppUserMapper users;
    public ProductManagementService(ProductScopeService scope,ProductMapper products,AppUserMapper users) {this.scope=scope;this.products=products;this.users=users;}

    public ProductResponse create(@NotNull @Valid CreateProductRequest request) {
        Long publisher=scope.requirePublisher();validateShape(request);
        var activity=scope.activity(request.activityId());
        if(!publisher.equals(activity.getPublisherId())){throw new ProductException(PRODUCT_ACCESS_DENIED,"Only the activity owner can create its products");}
        if(!List.of("DRAFT","PUBLISHED").contains(activity.getStatus())){throw new ProductException(PRODUCT_ACTIVITY_UNAVAILABLE,"Activity does not allow new products");}
        if("TICKET_REQUIRED".equals(request.qualificationType()) && !"PAID".equals(activity.getType())) {
            throw new ProductException(PRODUCT_INVALID_CONFIGURATION,"Ticket qualification requires a PAID activity");
        }
        scope.session(request.qualificationSessionId(),request.activityId());
        var values=new HashMap<String,Object>();values.put("publisherId",publisher);values.put("activityId",request.activityId());
        values.put("name",request.name().strip());values.put("description",request.description());values.put("qualificationType",request.qualificationType());
        values.put("qualificationScope",request.qualificationScope());values.put("qualificationSessionId",request.qualificationSessionId());
        values.put("purchaseLimit",request.purchaseLimit());values.put("refundPolicy",request.refundPolicy());
        changed(products.insert(values));return response(((Number)values.get("productId")).longValue());
    }
    public Sku addSku(@NotNull @Positive Long id,@NotNull @Valid SaveSkuRequest request) {
        requireDraft(scope.owned(id,true).product());var values=skuValues(id,request);
        try {changed(products.insertSku(values));} catch(DuplicateKeyException conflict){throw duplicateSku();}
        return products.sku(id,((Number)values.get("skuId")).longValue());
    }
    public Sku updateSku(@NotNull @Positive Long id,@NotNull @Positive Long skuId,@NotNull @Valid SaveSkuRequest request) {
        requireDraft(scope.owned(id,true).product());requireSku(id,skuId);var values=skuValues(id,request);values.put("skuId",skuId);
        try {changed(products.updateSku(values));} catch(DuplicateKeyException conflict){throw duplicateSku();}return products.sku(id,skuId);
    }
    public ProductResponse publish(@NotNull @Positive Long id) {
        var owned=scope.owned(id,true);
        if(!"PUBLISHED".equals(owned.activity().getStatus())){throw new ProductException(PRODUCT_ACTIVITY_UNAVAILABLE,"Product publication requires a published activity");}
        if(products.skus(id).stream().noneMatch(sku -> "ACTIVE".equals(sku.status()))){throw new ProductException(PRODUCT_NOT_READY,"At least one ACTIVE SKU is required");}
        if(!"ON_SALE".equals(owned.product().status())){changed(products.publish(id));}return response(id);
    }
    public ProductResponse takeOffSale(@NotNull @Positive Long id) {
        var owned=scope.owned(id,true);
        if("DRAFT".equals(owned.product().status())){throw new ProductException(PRODUCT_INVALID_STATE,"A draft has not been published");}
        if("ON_SALE".equals(owned.product().status())){changed(products.takeOffSale(id));}return response(id);
    }
    public List<Long> addMember(@NotNull @Positive Long id,@NotNull @Positive Long userId) {
        requireWhitelist(scope.owned(id,true).product());var user=users.findByIdForShare(userId);
        if(user==null || !"ACTIVE".equals(user.getStatus())){throw new ProductException(PRODUCT_MEMBER_NOT_FOUND,"An active target account is required");}
        products.addMember(id,userId);return products.members(id);
    }
    public List<Long> removeMember(@NotNull @Positive Long id,@NotNull @Positive Long userId) {
        requireWhitelist(scope.owned(id,true).product());products.removeMember(id,userId);return products.members(id);
    }
    private void requireWhitelist(Product p) {
        if(!"PRODUCT_WHITELIST".equals(p.qualificationType())){throw new ProductException(PRODUCT_INVALID_CONFIGURATION,"This product does not use a product whitelist");}
    }
    private void validateShape(CreateProductRequest r) {
        boolean ticket="TICKET_REQUIRED".equals(r.qualificationType());
        if(!ticket && (r.qualificationScope()!=null || r.qualificationSessionId()!=null)
                || ticket && (r.qualificationScope()==null || ("ACTIVITY".equals(r.qualificationScope()) && r.qualificationSessionId()!=null)
                || ("SESSION".equals(r.qualificationScope()) && r.qualificationSessionId()==null))) {
            throw new ProductException(PRODUCT_INVALID_CONFIGURATION,"Qualification type and scope fields do not match");
        }
    }
    private void requireDraft(Product p) {
        if(!"DRAFT".equals(p.status()) || p.firstOnSaleAt()!=null){throw new ProductException(PRODUCT_NOT_DRAFT,"SKU configuration can only change before first publication");}
    }
    private Sku requireSku(Long id,Long skuId) {var sku=products.sku(id,skuId);if(sku==null){throw new ProductException(SKU_NOT_FOUND,"SKU not found in this product");}return sku;}
    private Map<String,Object> skuValues(Long id,SaveSkuRequest request) {
        var values=new HashMap<String,Object>();values.put("productId",id);values.put("name",request.name().strip());values.put("price",request.price());values.put("stock",request.stock());return values;
    }
    private ProductResponse response(Long id){return ProductResponse.from(products.find(id),products.skus(id));}
    private ProductException duplicateSku(){return new ProductException(SKU_ALREADY_EXISTS,"SKU name already exists in this product");}
    private void changed(int rows){if(rows!=1){throw new ProductException(PRODUCT_INVALID_STATE,"Product resource did not change exactly once");}}
}
