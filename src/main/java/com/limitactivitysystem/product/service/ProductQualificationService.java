package com.limitactivitysystem.product.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.QualificationResult;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;
import java.sql.Connection;

@Service
@Validated
public class ProductQualificationService {
    private final ProductScopeService scope;private final ProductMapper products;private final AppUserMapper users;private final CurrentUserProvider current;
    public ProductQualificationService(ProductScopeService scope,ProductMapper products,AppUserMapper users,CurrentUserProvider current){this.scope=scope;this.products=products;this.users=users;this.current=current;}
    /** userId is a trusted internal argument, never an HTTP-supplied actor. Joins an existing RC business transaction. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public QualificationResult check(@NotNull @Positive Long userId,@NotNull @Positive Long productId){return evaluate(userId,productId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public QualificationResult previewCurrent(@NotNull @Positive Long productId){return evaluate(current.getAuthenticatedUserId(),productId);}
    private QualificationResult evaluate(Long userId,Long id){
        if(!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())){
            throw new IllegalTransactionStateException("Product qualification requires READ_COMMITTED");
        }
        var user=users.findByIdForShare(userId);
        if(user==null || !"ACTIVE".equals(user.getStatus())){return new QualificationResult(userId,id,false,"USER_INACTIVE");}
        var resolved=scope.resolve(id,false);var product=resolved.product();
        if(!scope.visible(resolved)){return new QualificationResult(userId,id,false,"PRODUCT_NOT_ON_SALE");}
        String reason=switch(product.qualificationType()){
            case "PUBLIC" -> "ALLOWED";
            case "PRODUCT_WHITELIST" -> products.isMember(id,userId)>0 ? "ALLOWED" : "PRODUCT_WHITELIST_REQUIRED";
            case "TICKET_REQUIRED" -> products.validTickets(userId,product.activityId(),product.qualificationSessionId())>0 ? "ALLOWED" : "TICKET_REQUIRED";
            default -> throw new IllegalStateException("Stored product qualification type is invalid");
        };
        return new QualificationResult(userId,id,"ALLOWED".equals(reason),reason);
    }
}
