package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.product.dto.*;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.service.*;
import com.limitactivitysystem.security.JwtTestTokens;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={JwtTestTokens.SECRET_PROPERTY,"spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(ProductCatalogMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="DB_PASSWORD",matches=".+")
class ProductCatalogMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired ProductQualificationService qualification;
    @Autowired ProductManagementService management;
    @Autowired SchemaObservation schema;
    @Autowired FailureObservation failures;
    private Activity paid,other,free;
    private ActivitySession first,second,otherSession,freeSession;
    private SessionZone paidZone,giftZone;
    private boolean corrupt;
    private static final Map<String,Object> AUDIT=new ConcurrentSkipListMap<>();
    @BeforeAll static void reset(){AUDIT.clear();}
    @AfterAll static void save() throws Exception {
        var path=Path.of("target","verification","day14-product-reconciliation.json");Files.createDirectories(path.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("script","tests/sql/product_invariants.sql","cases",AUDIT.size(),"checks",AUDIT));
    }
    @BeforeEach void createPublishedActivityScopes() throws Exception {
        paid=activity(publisher,"PAID");first=session(paid);second=session(paid);
        paidZone=insertZone(first,"PAID",4,4);giftZone=insertZone(first,"GIFT",2,2);insertZone(second,"PAID",2,2);publish(paid,publisher).andExpect(status().isNoContent());
        other=activity(otherPublisher,"PAID");otherSession=session(other);insertZone(otherSession,"PAID",2,2);publish(other,otherPublisher).andExpect(status().isNoContent());
        free=activity(publisher,"FREE");freeSession=session(free);publish(free,publisher).andExpect(status().isNoContent());
    }
    @AfterEach void reconcileAndClearCatalog(TestInfo info) throws Exception {
        failures.point.remove();failures.afterShare.remove();failures.beforeLock.remove();
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var rows=violations();
            AUDIT.put(info.getDisplayName(),Map.of("corruptFixture",corrupt,"violations",rows.size()));
            if(corrupt){assertThat(rows).isNotEmpty();}else{
                assertThat(rows).isEmpty();RefundInvariantAssertions.assertConsistent(jdbc);FreeReservationInvariantAssertions.assertConsistent(jdbc);
                assertThat(scriptViolations("gift_invariants.sql")).isEmpty();
            }
        } finally {
            jdbc.update("DELETE FROM product_whitelist_member");jdbc.update("DELETE FROM sku");jdbc.update("DELETE FROM product");
            jdbc.update("DELETE FROM ticket_refund_item");jdbc.update("DELETE FROM ticket_refund_request");
            jdbc.update("DELETE FROM whitelist_member");jdbc.update("DELETE FROM whitelist_config");
            jdbc.update("DELETE FROM gift_inventory_operation_item");jdbc.update("DELETE FROM gift_inventory_operation");
            jdbc.update("DELETE FROM free_reservation");jdbc.update("DELETE FROM free_session_quota");
        }
    }
    @Test void v8MigrationPreservesExistingTablesAndUsesNoPhysicalForeignKeys() throws Exception {
        assertThat(schema.before).isEqualTo(schema.after);assertThat(schema.executed).isEqualTo(1);assertThat(schema.repeated).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()",Integer.class)).isZero();
        evidence("day14-product-migration",Map.of("beforeVersion",7,"afterVersion",8,"newTables",3,"businessTables",20,"existingTableDefinitionsUnchanged",17,"physicalForeignKeys",0,"repeatedMigrations",0));
    }
    @Test void publishAndReadBlackMZeroStockWithoutSubstitutingWhiteL() throws Exception {
        long id=product(body("PUBLIC",null,null));
        var black=sku(id,"BLACK-M",0);var white=sku(id,"WHITE-L",20);
        mvc.perform(get("/products/{id}",id)).andExpect(status().isNotFound());
        own(id).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.publisherId").value(publisher.getId()));
        var live=publishProduct(id);
        assertThat(live.path("skus").get(0).path("remainsStock").asInt()).isZero();assertThat(live.path("skus").get(1).path("remainsStock").asInt()).isEqualTo(20);
        mvc.perform(get("/products/{id}/skus/{sku}",id,black.path("skuId").asLong())).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("BLACK-M")).andExpect(jsonPath("$.remainsStock").value(0));
        mvc.perform(get("/activities/{id}/products",paid.getActivityId())).andExpect(status().isOk()).andExpect(jsonPath("$[0].productId").value(id));
        mvc.perform(get("/publisher/products").header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isOk()).andExpect(jsonPath("$[0].productId").value(id));
        mvc.perform(get("/publisher/products/{id}/skus",id).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        allowed(id,true,"ALLOWED");
        evidence("day14-sku-no-substitution",Map.of("productId",id,"blackM",black,"whiteL",white,"blackMRemaining",0,"whiteLRemaining",20));
    }
    @Test void publicReadsAndOwnQualificationPreviewDoNotWriteAnyResources() throws Exception {
        long id=live("PUBLIC",null,null);var before=snapshot();
        mvc.perform(get("/products/{id}",id)).andExpect(status().isOk());
        mvc.perform(get("/products/{id}/qualification",id)).andExpect(status().isUnauthorized());
        allowed(id,true,"ALLOWED");
        mvc.perform(get("/products/{id}/qualification",id).param("userId",otherPublisher.getId().toString()).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void publicationRequiresSkuAndPublishedParentAndOffSaleHidesOnlyTheProduct() throws Exception {
        long id=product(body("PUBLIC",null,null));
        postJson("/publisher/products/"+id+"/publish",publisher,Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_READY"));
        sku(id,"only",0);var first=publishProduct(id);var before=snapshot();assertThat(publishProduct(id)).isEqualTo(first);assertThat(snapshot()).isEqualTo(before);
        postJson("/publisher/products/"+id+"/off-sale",publisher,Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFF_SALE"));
        mvc.perform(get("/products/{id}",id)).andExpect(status().isNotFound());allowed(id,false,"PRODUCT_NOT_ON_SALE");
        mvc.perform(get("/activities/{id}/products",paid.getActivityId())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(publishProduct(id).path("firstOnSaleAt")).isEqualTo(first.path("firstOnSaleAt"));
        var draft=insertDraft(publisher,"PAID");var request=body("PUBLIC",null,null);request.put("activityId",draft.getActivityId());long draftProduct=product(request);sku(draftProduct,"draft-sku",1);
        postJson("/publisher/products/"+draftProduct+"/publish",publisher,Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ACTIVITY_UNAVAILABLE"));
    }
    @Test void skuConfigurationChangesOnlyInDraftAndUsesExactDecimals() throws Exception {
        long id=product(body("PUBLIC",null,null));long sku=sku(id,"original",0).path("skuId").asLong();
        var edited=Map.of("name","changed","price",new BigDecimal("99.01"),"stock",20);
        mvc.perform(put("/publisher/products/{id}/skus/{sku}",id,sku).header(HttpHeaders.AUTHORIZATION,bearer(publisher)).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(edited)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainsStock").value(20));
        assertThat(jdbc.queryForObject("SELECT price FROM sku WHERE sku_id=?",BigDecimal.class,sku)).isEqualByComparingTo("99.01");
        publishProduct(id);
        for(boolean offSale:List.of(false,true)){
            if(offSale){postJson("/publisher/products/"+id+"/off-sale",publisher,Map.of()).andExpect(status().isOk());}
            var before=snapshot();
            postJson("/publisher/products/"+id+"/skus",publisher,edited).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_DRAFT"));
            mvc.perform(put("/publisher/products/{id}/skus/{sku}",id,sku).header(HttpHeaders.AUTHORIZATION,bearer(publisher)).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(edited))).andExpect(status().isConflict());
            assertThat(snapshot()).isEqualTo(before);
        }
    }
    @Test void productWhitelistIsIndependentAndPublicDoesNotInheritActivityRestrictions() throws Exception {
        jdbc.update("INSERT INTO whitelist_config(scope_type,scope_id) VALUES('ACTIVITY',?)",paid.getActivityId());
        long open=live("PUBLIC",null,null), listed=live("PRODUCT_WHITELIST",null,null);
        allowed(open,true,"ALLOWED");allowed(listed,false,"PRODUCT_WHITELIST_REQUIRED");
        postJson("/publisher/products/"+listed+"/whitelist/members",publisher,Map.of("userId",buyer.getId())).andExpect(status().isCreated());
        postJson("/publisher/products/"+listed+"/whitelist/members",publisher,Map.of("userId",buyer.getId())).andExpect(status().isCreated());allowed(listed,true,"ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_whitelist_member",Integer.class)).isEqualTo(1);
        mvc.perform(delete("/publisher/products/{id}/whitelist/members/{user}",listed,buyer.getId()).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isOk());
        var config=jdbc.queryForObject("SELECT config_id FROM whitelist_config WHERE scope_type='ACTIVITY' AND scope_id=?",Long.class,paid.getActivityId());
        jdbc.update("INSERT INTO whitelist_member(config_id,user_id) VALUES(?,?)",config,buyer.getId());allowed(listed,false,"PRODUCT_WHITELIST_REQUIRED");
    }
    @Test void partialRefundStillProvidesTicketQualificationUntilTheLastValidTicketIsRefunded() throws Exception {
        long activityProduct=live("TICKET_REQUIRED","ACTIVITY",null), firstProduct=live("TICKET_REQUIRED","SESSION",first.getActivitySessionId()), secondProduct=live("TICKET_REQUIRED","SESSION",second.getActivitySessionId());
        allowed(activityProduct,false,"TICKET_REQUIRED");
        long order=payTwoTickets();allowed(activityProduct,true,"ALLOWED");allowed(firstProduct,true,"ALLOWED");allowed(secondProduct,false,"TICKET_REQUIRED");
        var tickets=jdbc.queryForList("SELECT ticket_id FROM ticket ORDER BY ticket_id",Long.class);refund(order,tickets.get(0),"partial");
        assertThat(orders.findById(order).getStatus()).isEqualTo("PARTIALLY_REFUNDED");allowed(activityProduct,true,"ALLOWED");allowed(firstProduct,true,"ALLOWED");
        refund(order,tickets.get(1),"last");assertThat(orders.findById(order).getStatus()).isEqualTo("REFUNDED");allowed(activityProduct,false,"TICKET_REQUIRED");allowed(firstProduct,false,"TICKET_REQUIRED");
        evidence("day14-product-valid-ticket-qualification",Map.of("orderId",order,"partialStillQualified",true,"lastRefundQualified",false,"scopeIsolation",true));
    }
    @Test void aPaidOrderWithoutAnyValidTicketDoesNotGrantQualification() throws Exception {
        long id=live("TICKET_REQUIRED","ACTIVITY",null);long order=payTwoTickets();
        // Deliberately inconsistent intermediate fixture proves PAID alone is insufficient. Restore before reconciliation.
        jdbc.update("UPDATE ticket SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP(3)");
        try {assertThat(orders.findById(order).getStatus()).isEqualTo("PAID");allowed(id,false,"TICKET_REQUIRED");}
        finally {jdbc.update("UPDATE ticket SET status='VALID',refunded_at=NULL");}
        assertThat(qualification.check(otherPublisher.getId(),id).allowed()).isFalse();
    }
    @Test void activityScopeAcceptsAnotherSessionButNeverAnotherActivity() throws Exception {
        long all=live("TICKET_REQUIRED","ACTIVITY",null),one=live("TICKET_REQUIRED","SESSION",first.getActivitySessionId()),two=live("TICKET_REQUIRED","SESSION",second.getActivitySessionId());
        payTickets(otherSession,zoneMapper.findBySessionId(otherSession.getActivitySessionId()).get(0),"other-activity");allowed(all,false,"TICKET_REQUIRED");
        payTickets(second,zoneMapper.findBySessionId(second.getActivitySessionId()).get(0),"same-activity-other-session");
        allowed(all,true,"ALLOWED");allowed(one,false,"TICKET_REQUIRED");allowed(two,true,"ALLOWED");
    }
    @Test void qualificationReadAndWhitelistRemovalCoordinateOnTheActualProductRow() throws Exception {
        long id=live("PRODUCT_WHITELIST",null,null);postJson("/publisher/products/"+id+"/whitelist/members",publisher,Map.of("userId",buyer.getId())).andExpect(status().isCreated());
        var held=gate();var release=gate();var attempting=gate();var readerId=new java.util.concurrent.atomic.AtomicLong();var writerId=new java.util.concurrent.atomic.AtomicLong();
        var reading=async(() -> {
            failures.afterShare.set(() -> {readerId.set(connectionId());held.countDown();await(release);});
            try{return mvc.perform(get("/products/{id}/qualification",id).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();}
            finally{failures.afterShare.remove();}
        });await(held);
        var writing=async(() -> {
            failures.beforeLock.set(() -> {writerId.set(connectionId());attempting.countDown();});
            try{return mvc.perform(delete("/publisher/products/{id}/whitelist/members/{user}",id,buyer.getId()).header(HttpHeaders.AUTHORIZATION,bearer(publisher))).andExpect(status().isOk()).andReturn();}
            finally{failures.beforeLock.remove();}
        });
        try{await(attempting);evidence("day14-product-whitelist-lock-wait",awaitDatabaseLockWait(writerId.get(),readerId.get(),"product"));}
        finally{release.countDown();}
        assertThat(objectMapper.readTree(reading.get(15,TimeUnit.SECONDS)).path("allowed").asBoolean()).isTrue();writing.get(15,TimeUnit.SECONDS);allowed(id,false,"PRODUCT_WHITELIST_REQUIRED");
    }
    @Test void qualificationCannotJoinARepeatableReadCallerAndReuseAnOldSnapshot() throws Exception {
        long id=live("PUBLIC",null,null);var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.execute(status -> qualification.check(buyer.getId(),id))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"NULL_SCOPE","UNKNOWN_TYPE","LIMIT","POLICY","PUBLICATION","SKU_NEGATIVE","SKU_OVER_TOTAL","SKU_PRICE","MEMBER_ID"})
    void databaseConstraintsRejectMalformedRowsWithoutLeavingPartialConfiguration(String kind) throws Exception {
        long id=product(body("PUBLIC",null,null));long sku=sku(id,"constraint",1).path("skuId").asLong();var before=snapshot();
        assertThatThrownBy(() -> {
            switch(kind){
                case "NULL_SCOPE"->jdbc.update("UPDATE product SET qualification_type='TICKET_REQUIRED',qualification_scope=NULL WHERE product_id=?",id);
                case "UNKNOWN_TYPE"->jdbc.update("UPDATE product SET qualification_type='unknown' WHERE product_id=?",id);
                case "LIMIT"->jdbc.update("UPDATE product SET purchase_limit=0 WHERE product_id=?",id);
                case "POLICY"->jdbc.update("UPDATE product SET refund_policy_template='CUSTOM' WHERE product_id=?",id);
                case "PUBLICATION"->jdbc.update("UPDATE product SET status='ON_SALE' WHERE product_id=?",id);
                case "SKU_NEGATIVE"->jdbc.update("UPDATE sku SET remains_stock=-1 WHERE sku_id=?",sku);
                case "SKU_OVER_TOTAL"->jdbc.update("UPDATE sku SET remains_stock=2 WHERE sku_id=?",sku);
                case "SKU_PRICE"->jdbc.update("UPDATE sku SET price=0 WHERE sku_id=?",sku);
                default->jdbc.update("INSERT INTO product_whitelist_member(product_id,user_id) VALUES(?,0)",id);
            }
        }).isInstanceOf(DataAccessException.class);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void onlyTheCurrentOwningActivePublisherCanManageTheCatalog() throws Exception {
        long id=product(body("PUBLIC",null,null));var before=snapshot();
        mvc.perform(post("/publisher/products").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body("PUBLIC",null,null)))).andExpect(status().isUnauthorized());
        for(var actor:List.of(buyer,otherPublisher)){
            postJson("/publisher/products/"+id+"/skus",actor,Map.of("name","bad","price",99,"stock",1)).andExpect(status().isForbidden());
            ownAs(id,actor).andExpect(status().isForbidden());postJson("/publisher/products/"+id+"/publish",actor,Map.of()).andExpect(status().isForbidden());
        }
        postJson("/publisher/products",buyer,body("PUBLIC",null,null)).andExpect(status().isForbidden());
        var foreign=body("PUBLIC",null,null);foreign.put("activityId",other.getActivityId());postJson("/publisher/products",publisher,foreign).andExpect(status().isForbidden());
        assertThat(snapshot()).isEqualTo(before);
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",publisher.getId());own(id).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings={"CREATE_PRODUCT","CREATE_SKU","EDIT_SKU","PUBLISH","OFF_SALE","ADD_MEMBER","REMOVE_MEMBER","READ_OWN"})
    void publisherACannotUsePublisherBsActivityProductOrSku(String operation) throws Exception {
        var request=body("PRODUCT_WHITELIST",null,null);request.put("activityId",other.getActivityId());
        var created=postJson("/publisher/products",otherPublisher,request).andExpect(status().isCreated()).andReturn().getResponse();
        long id=objectMapper.readTree(created.getContentAsString()).path("productId").asLong();
        var skuBody=Map.of("name","B-owned-SKU","price",new BigDecimal("99.00"),"stock",20);
        var saved=postJson("/publisher/products/"+id+"/skus",otherPublisher,skuBody).andExpect(status().isCreated()).andReturn().getResponse();
        long skuId=objectMapper.readTree(saved.getContentAsString()).path("skuId").asLong();
        if(operation.equals("OFF_SALE")){postJson("/publisher/products/"+id+"/publish",otherPublisher,Map.of()).andExpect(status().isOk());}
        if(operation.equals("REMOVE_MEMBER")){postJson("/publisher/products/"+id+"/whitelist/members",otherPublisher,Map.of("userId",buyer.getId())).andExpect(status().isCreated());}
        var before=snapshot();
        var denied=switch(operation){
            case "CREATE_PRODUCT" -> postJson("/publisher/products",publisher,request);
            case "CREATE_SKU" -> postJson("/publisher/products/"+id+"/skus",publisher,skuBody);
            case "EDIT_SKU" -> mvc.perform(put("/publisher/products/{id}/skus/{sku}",id,skuId)
                    .header(HttpHeaders.AUTHORIZATION,bearer(publisher)).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of("name","forged","price",1,"stock",0))));
            case "PUBLISH" -> postJson("/publisher/products/"+id+"/publish",publisher,Map.of());
            case "OFF_SALE" -> postJson("/publisher/products/"+id+"/off-sale",publisher,Map.of());
            case "ADD_MEMBER" -> postJson("/publisher/products/"+id+"/whitelist/members",publisher,Map.of("userId",buyer.getId()));
            case "REMOVE_MEMBER" -> mvc.perform(delete("/publisher/products/{id}/whitelist/members/{user}",id,buyer.getId()).header(HttpHeaders.AUTHORIZATION,bearer(publisher)));
            default -> own(id);
        };
        denied.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PRODUCT_ACCESS_DENIED"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT activity_id FROM product WHERE product_id=?",Long.class,id)).isEqualTo(other.getActivityId());
        assertThat(jdbc.queryForObject("SELECT publisher_id FROM product WHERE product_id=?",Long.class,id)).isEqualTo(otherPublisher.getId());
        evidence("day14-owner-denied-"+operation,Map.of("operation",operation,"http",403,"code","PRODUCT_ACCESS_DENIED",
                "actorPublisher",publisher.getId(),"ownerPublisher",otherPublisher.getId(),"catalogSnapshotUnchanged",true));
    }
    @Test void anOwnedProductPathCannotBeUsedToEditAnotherPublishersSku() throws Exception {
        long owned=product(body("PUBLIC",null,null));sku(owned,"A-owned",1);
        var request=body("PUBLIC",null,null);request.put("activityId",other.getActivityId());
        var created=postJson("/publisher/products",otherPublisher,request).andExpect(status().isCreated()).andReturn().getResponse();
        long foreign=objectMapper.readTree(created.getContentAsString()).path("productId").asLong();
        var saved=postJson("/publisher/products/"+foreign+"/skus",otherPublisher,Map.of("name","B-owned","price",99,"stock",20)).andExpect(status().isCreated()).andReturn().getResponse();
        long foreignSku=objectMapper.readTree(saved.getContentAsString()).path("skuId").asLong();var before=snapshot();
        mvc.perform(put("/publisher/products/{id}/skus/{sku}",owned,foreignSku).header(HttpHeaders.AUTHORIZATION,bearer(publisher))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(Map.of("name","forged","price",1,"stock",0))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SKU_NOT_FOUND"));
        assertThat(snapshot()).isEqualTo(before);
        evidence("day14-owner-foreign-sku",Map.of("http",404,"code","SKU_NOT_FOUND","catalogSnapshotUnchanged",true));
    }
    @ParameterizedTest @ValueSource(strings={"PUBLIC_ACTIVE","WHITELIST_IN","WHITELIST_OUT","ACTIVITY_VALID","ACTIVITY_REFUNDED_ONLY","SESSION_OTHER","SESSION_VALID"})
    void acceptanceMatrixUsesCurrentIndependentQualificationAndRealValidTickets(String scenario) throws Exception {
        String type=scenario.startsWith("PUBLIC")?"PUBLIC":scenario.startsWith("WHITELIST")?"PRODUCT_WHITELIST":"TICKET_REQUIRED";
        String scope=type.equals("TICKET_REQUIRED")?(scenario.startsWith("SESSION")?"SESSION":"ACTIVITY"):null;
        Long sessionId="SESSION".equals(scope)?first.getActivitySessionId():null;long id=live(type,scope,sessionId);
        if(scenario.equals("WHITELIST_IN")){postJson("/publisher/products/"+id+"/whitelist/members",publisher,Map.of("userId",buyer.getId())).andExpect(status().isCreated());}
        if(scenario.equals("ACTIVITY_VALID") || scenario.equals("SESSION_VALID")){payTwoTickets();}
        if(scenario.equals("SESSION_OTHER")){payTickets(second,zoneMapper.findBySessionId(second.getActivitySessionId()).get(0),"scope-other");}
        if(scenario.equals("ACTIVITY_REFUNDED_ONLY")){
            long order=payTwoTickets();var ids=jdbc.queryForList("SELECT ticket_id FROM ticket ORDER BY ticket_id",Long.class);
            refund(order,ids.get(0),"matrix-first");refund(order,ids.get(1),"matrix-last");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status='VALID'",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status='REFUNDED'",Integer.class)).isEqualTo(2);
        }
        boolean expected=Set.of("PUBLIC_ACTIVE","WHITELIST_IN","ACTIVITY_VALID","SESSION_VALID").contains(scenario);
        String reason=expected?"ALLOWED":type.equals("PRODUCT_WHITELIST")?"PRODUCT_WHITELIST_REQUIRED":"TICKET_REQUIRED";
        var before=snapshot();allowed(id,expected,reason);assertThat(snapshot()).isEqualTo(before);
        evidence("day14-qualification-matrix-"+scenario,Map.of("scenario",scenario,"qualificationType",type,"qualificationScope",scope==null?"NONE":scope,
                "http",200,"allowed",expected,"reason",reason,"catalogAndTicketSnapshotUnchanged",true));
    }
    @ParameterizedTest @ValueSource(strings={"STANDARD_7D","SPECIAL_NO_REASON_RETURN"})
    void presetTemplatesRoundTripThroughDatabaseAndApiWithoutUniversalReturnDenial(String template) throws Exception {
        var request=body("PUBLIC",null,null);request.put("refundPolicy",template);long id=product(request);sku(id,"template-SKU",20);publishProduct(id);
        var response=mvc.perform(get("/products/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.refundPolicy").value(template))
                .andExpect(jsonPath("$.refundPolicyVersion").value(1)).andExpect(jsonPath("$.returnable").doesNotExist()).andReturn().getResponse();
        var result=objectMapper.readTree(response.getContentAsString());boolean special=template.equals("SPECIAL_NO_REASON_RETURN");
        assertThat(jdbc.queryForObject("SELECT refund_policy_template FROM product WHERE product_id=?",String.class,id)).isEqualTo(template);
        assertThat(result.path("requiresPolicyAcknowledgement").asBoolean()).isEqualTo(special);
        String notice=result.path("refundPolicyNotice").asText();
        if(special){assertThat(notice).contains("普通无理由退货","质量问题","失去持票资格","活动取消");}
        else{assertThat(notice).contains("七日","签收");}
        allowed(id,true,"ALLOWED");
        evidence("day14-policy-template-"+template,Map.of("template",template,"databaseMatchesApi",true,"requiresPolicyAcknowledgement",special,
                "notice",notice,"universalReturnableFlag",false,"publicPurchaseQualification",true));
    }
    @Test void day14CatalogDoesNotImplementProductTransactionsOrConsumeSkuStock() throws Exception {
        long id=live("PUBLIC",null,null);var before=snapshot();allowed(id,true,"ALLOWED");
        for(String path:List.of("/product-orders","/product-orders/"+id+"/pay","/publisher/products/"+id+"/ship")){
            postJson(path,path.startsWith("/publisher/")?publisher:buyer,Map.of()).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('product_order','product_order_item','user_product_purchase','user_sku_purchase','user_activity_qualification_guard')",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE remains_stock<>total_stock",Integer.class)).isZero();assertThat(snapshot()).isEqualTo(before);
        evidence("day14-catalog-scope",Map.of("productOrderRoutes",false,"productPayment",false,"productShipping",false,"productPurchaseCounterTables",0,
                "qualificationGuardTables",0,"skuStockConsumed",0,"catalogSnapshotUnchanged",true));
    }
    @ParameterizedTest @ValueSource(strings={"USER","DISABLED"})
    void serviceRechecksRoleAndAccountAfterAuthentication(String change) throws Exception {
        var r=new CreateProductRequest(paid.getActivityId(),"test",null,"PUBLIC",null,null,2,"STANDARD_7D");
        assertThatThrownBy(() -> asPublisher(() -> {
            jdbc.update("UPDATE app_user SET "+(change.equals("USER")?"role":"status")+"=? WHERE id=?",change,publisher.getId());return management.create(r);
        })).isInstanceOf(ProductException.class);
    }
    @ParameterizedTest @ValueSource(strings={"publisherId","status","createdAt","firstOnSaleAt","refundPolicyVersion","totalStock","userId"})
    void clientsCannotSetProductIdentityOrState(String field) throws Exception {
        var r=body("PUBLIC",null,null);r.put(field,null);var before=snapshot();postJson("/publisher/products",publisher,r).andExpect(status().isBadRequest());assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @CsvSource({"PUBLIC,ACTIVITY,NONE","PUBLIC,NONE,FIRST","PRODUCT_WHITELIST,SESSION,FIRST","TICKET_REQUIRED,NONE,NONE","TICKET_REQUIRED,ACTIVITY,FIRST","TICKET_REQUIRED,SESSION,NONE","TICKET_REQUIRED,SESSION,OTHER"})
    void qualificationShapeAndRealSessionScopeAreValidated(String type,String scope,String session) throws Exception {
        Long sessionId=switch(session){case "FIRST"->first.getActivitySessionId();case "OTHER"->otherSession.getActivitySessionId();default->null;};
        postJson("/publisher/products",publisher,body(type,scope.equals("NONE")?null:scope,sessionId)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRODUCT_INVALID_CONFIGURATION"));
    }
    @Test void freeReservationsAndGiftSeatsCannotStandInForOrdinaryTickets() throws Exception {
        var r=body("TICKET_REQUIRED","ACTIVITY",null);r.put("activityId",free.getActivityId());postJson("/publisher/products",publisher,r).andExpect(status().isBadRequest());
        long id=live("TICKET_REQUIRED","ACTIVITY",null);
        postJson("/sessions/"+freeSession.getActivitySessionId()+"/reservations",buyer,Map.of()).andExpect(status().isCreated());allowed(id,false,"TICKET_REQUIRED");
        postJson("/publisher/gift-zones/"+giftZone.getZoneId()+"/allocations",publisher,Map.of("requestId","catalog-gift","quantity",1)).andExpect(status().isCreated());allowed(id,false,"TICKET_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings={"ZERO_PRICE","NEGATIVE_STOCK","FRACTIONAL_STOCK","STRING_STOCK","OVERFLOW_STOCK","PRICE_PRECISION","STRING_PRICE","EXTRA_FIELD","EMPTY_NAME"})
    void invalidSkuPayloadsNeverMutateResources(String kind) throws Exception {
        long id=product(body("PUBLIC",null,null));var values=new HashMap<String,Object>(Map.of("name","valid","price",99,"stock",1));
        switch(kind){case "ZERO_PRICE"->values.put("price",0);case "NEGATIVE_STOCK"->values.put("stock",-1);case "FRACTIONAL_STOCK"->values.put("stock",1.5);case "STRING_STOCK"->values.put("stock","1");case "OVERFLOW_STOCK"->values.put("stock",2147483648L);case "PRICE_PRECISION"->values.put("price",new BigDecimal("1.001"));case "STRING_PRICE"->values.put("price","99.00");case "EXTRA_FIELD"->values.put("remainsStock",null);case "EMPTY_NAME"->values.put("name"," ");default->throw new IllegalArgumentException(kind);}
        var before=snapshot();postJson("/publisher/products/"+id+"/skus",publisher,values).andExpect(status().isBadRequest());assertThat(snapshot()).isEqualTo(before);
    }
    @Test void identicalSkuNamesAreSerializedAndCannotCreateTwoStockPools() throws Exception {
        long id=product(body("PUBLIC",null,null));var barrier=new CyclicBarrier(2);var values=Map.of("name"," BLACK-M ","price",99,"stock",20);
        var a=async(() -> {barrier.await(10,TimeUnit.SECONDS);return postJson("/publisher/products/"+id+"/skus",publisher,values).andReturn().getResponse().getStatus();});
        var b=async(() -> {barrier.await(10,TimeUnit.SECONDS);return postJson("/publisher/products/"+id+"/skus",publisher,values).andReturn().getResponse().getStatus();});
        assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE product_id=?",Integer.class,id)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"insert","insertSku","publish"})
    void failuresAfterCatalogWritesRollBackCompletely(String point) throws Exception {
        long id=0;if(!point.equals("insert")){id=product(body("PUBLIC",null,null));}if(point.equals("publish")){sku(id,"valid",1);}
        var before=snapshot();failures.point.set(point);
        try {if(point.equals("insert")){postJson("/publisher/products",publisher,body("PUBLIC",null,null)).andExpect(status().isInternalServerError());}
            else if(point.equals("insertSku")){postJson("/publisher/products/"+id+"/skus",publisher,Map.of("name","valid","price",99,"stock",1)).andExpect(status().isInternalServerError());}
            else{postJson("/publisher/products/"+id+"/publish",publisher,Map.of()).andExpect(status().isInternalServerError());}
        }finally{failures.point.remove();}assertThat(snapshot()).isEqualTo(before);
    }
    @Test void specialTemplateIsDisplayedWithoutErasingQualityOrQualificationLossHandling() throws Exception {
        var r=body("PUBLIC",null,null);r.put("refundPolicy","SPECIAL_NO_REASON_RETURN");long id=product(r);sku(id,"special",1);publishProduct(id);
        mvc.perform(get("/products/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.requiresPolicyAcknowledgement").value(true))
                .andExpect(jsonPath("$.refundPolicyNotice").value(org.hamcrest.Matchers.containsString("质量问题")));
    }
    @Test void hiddenParentsAndForeignSkusCannotBeQueriedThroughPublicIds() throws Exception {
        long id=live("PUBLIC",null,null),otherProduct=product(body("PUBLIC",null,null));long otherSku=sku(otherProduct,"other",1).path("skuId").asLong();
        mvc.perform(get("/products/{id}/skus/{sku}",id,otherSku)).andExpect(status().isNotFound());
        jdbc.update("UPDATE activity SET status='CANCELLED' WHERE activity_id=?",paid.getActivityId());
        mvc.perform(get("/products/{id}",id)).andExpect(status().isNotFound());allowed(id,false,"PRODUCT_NOT_ON_SALE");
    }
    @Test void independentServiceQueriesRejectInactiveAccountsAndDoNotConsumeStock() throws Exception {
        long id=live("PUBLIC",null,null);var before=snapshot();assertThat(qualification.check(buyer.getId(),id).allowed()).isTrue();
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",buyer.getId());
        assertThat(qualification.check(buyer.getId(),id).reason()).isEqualTo("USER_INACTIVE");assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"product","sku","member"})
    void logicalOrphansAreDetectedByCatalogReconciliation(String table) throws Exception {
        long id=live("PRODUCT_WHITELIST",null,null);corrupt=true;
        switch(table){case "product"->jdbc.update("UPDATE product SET activity_id=? WHERE product_id=?",Long.MAX_VALUE,id);case "sku"->jdbc.update("UPDATE sku SET product_id=? WHERE product_id=?",Long.MAX_VALUE,id);default->jdbc.update("INSERT INTO product_whitelist_member(product_id,user_id) VALUES(?,?)",id,Long.MAX_VALUE);}
        assertThat(violations()).isNotEmpty();
    }
    private Activity activity(com.limitactivitysystem.identity.model.AppUser owner,String type){
        var a=insertDraft(owner,type);var now=now();jdbc.update("UPDATE activity SET open_time=?,close_time=? WHERE activity_id=?",now.minusDays(10),now.plusDays(1),a.getActivityId());return a;
    }
    private ActivitySession session(Activity activity){var s=insertSession(activity);jdbc.update("UPDATE activity_session SET start_time=?,end_time=?,max_tickets_per_user=? WHERE activity_session_id=?",now().plusDays(20),now().plusDays(20).plusHours(2),activity.getType().equals("PAID")?4:null,s.getActivitySessionId());return s;}
    private LocalDateTime now(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",LocalDateTime.class);}
    private Map<String,Object> body(String type,String scope,Long session){var r=new HashMap<String,Object>();r.put("activityId",paid.getActivityId());r.put("name","test-product");r.put("description","catalog test");r.put("qualificationType",type);r.put("qualificationScope",scope);r.put("qualificationSessionId",session);r.put("purchaseLimit",2);r.put("refundPolicy","STANDARD_7D");return r;}
    private long product(Map<String,Object> body) throws Exception {return objectMapper.readTree(postJson("/publisher/products",publisher,body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("productId").asLong();}
    private JsonNode sku(long id,String name,int stock) throws Exception {return objectMapper.readTree(postJson("/publisher/products/"+id+"/skus",publisher,Map.of("name",name,"price",99,"stock",stock)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
    private JsonNode publishProduct(long id) throws Exception {return objectMapper.readTree(postJson("/publisher/products/"+id+"/publish",publisher,Map.of()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private long live(String type,String scope,Long session) throws Exception {long id=product(body(type,scope,session));sku(id,"main",1);publishProduct(id);return id;}
    private org.springframework.test.web.servlet.ResultActions own(long id) throws Exception {return ownAs(id,publisher);}
    private org.springframework.test.web.servlet.ResultActions ownAs(long id,com.limitactivitysystem.identity.model.AppUser actor) throws Exception {return mvc.perform(get("/publisher/products/{id}",id).header(HttpHeaders.AUTHORIZATION,bearer(actor)));}
    private void allowed(long id,boolean expected,String reason) throws Exception {mvc.perform(get("/products/{id}/qualification",id).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(expected)).andExpect(jsonPath("$.reason").value(reason));}
    private long payTwoTickets() throws Exception {
        var seats=seatMapper.findByZoneId(paidZone.getZoneId()).subList(0,2);
        var items=seats.stream().map(s -> Map.of("zoneId",paidZone.getZoneId(),"allocationMode","SELECT_SEAT","requestedSeatId",s.getSeatId(),"quantity",1,"acceptNonAdjacent",false)).toList();
        long order=objectMapper.readTree(postJson("/ticket-orders",buyer,Map.of("requestId","catalog-tickets","sessionId",first.getActivitySessionId(),"items",items)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("orderId").asLong();
        mvc.perform(post("/ticket-orders/{id}/pay",order).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isOk());return order;
    }
    private void payTickets(ActivitySession session,SessionZone zone,String key) throws Exception {
        long seat=seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        var item=Map.of("zoneId",zone.getZoneId(),"allocationMode","SELECT_SEAT","requestedSeatId",seat,"quantity",1,"acceptNonAdjacent",false);
        var response=postJson("/ticket-orders",buyer,Map.of("requestId",key,"sessionId",session.getActivitySessionId(),"items",List.of(item))).andExpect(status().isCreated()).andReturn().getResponse();
        long id=objectMapper.readTree(response.getContentAsString()).path("orderId").asLong();mvc.perform(post("/ticket-orders/{id}/pay",id).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isOk());
    }
    private void refund(long order,long ticket,String key) throws Exception {
        var response=postJson("/ticket-refunds",buyer,Map.of("requestId",key,"orderId",order,"ticketIds",List.of(ticket),"reason","USER_REQUEST")).andExpect(status().isCreated()).andReturn().getResponse();
        long refund=objectMapper.readTree(response.getContentAsString()).path("refundId").asLong();mvc.perform(post("/ticket-refunds/{id}/process",refund).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andExpect(status().isOk());
    }
    private Map<String,Object> snapshot(){var result=new LinkedHashMap<String,Object>();for(String table:List.of("product","sku","product_whitelist_member","ticket","ticket_order","ticket_order_item","user_session_purchase")){result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));}return result;}
    private List<Map<String,Object>> violations() throws Exception {return scriptViolations("product_invariants.sql");}
    private List<Map<String,Object>> scriptViolations(String name) throws Exception {var sql=Files.readString(Path.of("tests","sql",name)).lines().filter(line->!line.startsWith("--")).collect(Collectors.joining("\n"));var rows=new ArrayList<Map<String,Object>>();for(String statement:sql.split(";")){if(!statement.isBlank()){rows.addAll(jdbc.queryForList(statement));}}return rows;}

    static class SchemaObservation {Map<String,String> before,after;int executed,repeated;}
    @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
    static class FailureObservation implements Interceptor {
        final ThreadLocal<String> point=new ThreadLocal<>();
        final ThreadLocal<Runnable> afterShare=new ThreadLocal<>(),beforeLock=new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();
            if(id.endsWith("ProductMapper.lock") && beforeLock.get()!=null){beforeLock.get().run();}
            var result=invocation.proceed();
            if(id.endsWith("ProductMapper.share") && afterShare.get()!=null){afterShare.get().run();}
            if(point.get()!=null && id.endsWith("ProductMapper."+point.get())){point.remove();assertThat(result).isEqualTo(1);throw new UncategorizedSQLException("Product test fault","after successful SQL",new SQLException("Injected"));}return result;
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class DatabaseConfiguration {
        @Bean SchemaObservation productSchemaObservation(){return new SchemaObservation();}
        @Bean FailureObservation productFailureObservation(){return new FailureObservation();}
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties,SchemaObservation observation) throws Exception {
            var source=new IsolatedQualificationMySqlDataSource(properties,6,List.of());
            try {
                var directory=Files.createDirectories(Path.of("target","product-migrations",UUID.randomUUID().toString()));
                for(String name:List.of("V1__create_schema.sql","V3__create_whitelist_tables.sql","V4__create_free_reservation_tables.sql","V5__create_ticket_refund_tables.sql","V6__add_refund_processing_results.sql","V7__add_gift_inventory_operations.sql","V8__create_product_catalog.sql")){
                    try(var input=new ClassPathResource("db/migration/"+name).getInputStream()){Files.copy(input,directory.resolve(name));}
                }
                String location="filesystem:"+directory.toAbsolutePath().toString().replace('\\','/');
                Flyway.configure().dataSource(source).locations(location).target(MigrationVersion.fromVersion("7")).load().migrate();var jdbc=new JdbcTemplate(source);
                var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class);
                observation.before=definitions(jdbc,tables);var flyway=Flyway.configure().dataSource(source).locations(location).load();observation.executed=flyway.migrate().migrationsExecuted;flyway.validate();observation.repeated=flyway.migrate().migrationsExecuted;observation.after=definitions(jdbc,tables);return source;
            }catch(Exception failure){source.close();throw failure;}
        }
        private static Map<String,String> definitions(JdbcTemplate jdbc,List<String> tables){var result=new LinkedHashMap<String,String>();tables.forEach(table->result.put(table,jdbc.queryForMap("SHOW CREATE TABLE "+table).get("Create Table").toString()));return result;}
    }
}
