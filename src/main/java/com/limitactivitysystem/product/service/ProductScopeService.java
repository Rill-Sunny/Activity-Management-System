package com.limitactivitysystem.product.service;

import com.limitactivitysystem.activity.mapper.*;
import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.product.model.Product;
import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Objects;
import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Transactional(propagation=Propagation.MANDATORY)
public class ProductScopeService {
    private final CurrentUserProvider current;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final ProductMapper products;
    public ProductScopeService(CurrentUserProvider current,AppUserMapper users,ActivityMapper activities,ActivitySessionMapper sessions,ProductMapper products) {
        this.current=current;this.users=users;this.activities=activities;this.sessions=sessions;this.products=products;
    }
    public Long requirePublisher() {
        Long id=current.getAuthenticatedUserId();var user=users.findByIdForShare(id);
        if(user==null || !"ACTIVE".equals(user.getStatus())){throw new ProductException(PRODUCT_USER_DISABLED,"Current account is not active");}
        if(!"PUBLISHER".equals(user.getRole())){throw new ProductException(PRODUCT_ACCESS_DENIED,"Publisher role is required");} return id;
    }
    public Activity activity(Long id) {
        var activity=activities.findByIdForShare(id);
        if(activity==null){throw new ProductException(PRODUCT_SCOPE_INVALID,"Product activity is missing");}return activity;
    }
    public void session(Long id,Long activityId) {
        if(id==null){return;}var session=sessions.findByIdForShare(id);
        if(session==null || !activityId.equals(session.getActivityId())){throw new ProductException(PRODUCT_INVALID_CONFIGURATION,"Qualification session must belong to the product activity");}
    }
    public Scope owned(Long id,boolean write) {
        Long publisher=requirePublisher();var scope=resolve(id,write);
        if(!publisher.equals(scope.product().publisherId()) || !publisher.equals(scope.activity().getPublisherId())) {
            throw new ProductException(PRODUCT_ACCESS_DENIED,"Only the activity's owning publisher can manage this product");
        }return scope;
    }
    public Scope resolve(Long id,boolean write) {
        return resolve(id, write, true);
    }
    public Scope resolveForPublicRead(Long id) {
        return resolve(id, false, false);
    }
    private Scope resolve(Long id,boolean write,boolean lockRead) {
        var discovered=products.find(id);if(discovered==null){throw new ProductException(PRODUCT_NOT_FOUND,"Product not found");}
        var activity=activity(discovered.activityId());
        try {session(discovered.qualificationSessionId(),activity.getActivityId());}
        catch(ProductException failure){throw new ProductException(PRODUCT_SCOPE_INVALID,"Stored qualification session is inconsistent");}
        var product=write ? products.lock(id) : lockRead ? products.share(id) : discovered;
        if(product==null){throw new ProductException(PRODUCT_NOT_FOUND,"Product not found");}
        if(!discovered.activityId().equals(product.activityId()) || !Objects.equals(discovered.qualificationSessionId(),product.qualificationSessionId())
                || !activity.getPublisherId().equals(product.publisherId())
                || ("TICKET_REQUIRED".equals(product.qualificationType()) && !"PAID".equals(activity.getType()))) {
            throw new ProductException(PRODUCT_SCOPE_INVALID,"Product relationships are inconsistent");
        }return new Scope(product,activity);
    }
    public boolean visible(Scope scope) {
        return "ON_SALE".equals(scope.product().status()) && ("PUBLISHED".equals(scope.activity().getStatus()) || "FINISHED".equals(scope.activity().getStatus()));
    }
    public record Scope(Product product,Activity activity) { }
}
