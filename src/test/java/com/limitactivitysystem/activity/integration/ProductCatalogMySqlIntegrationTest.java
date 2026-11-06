package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.activity.service.ActivityQueryService;
import com.limitactivitysystem.cache.CacheAsideService;
import com.limitactivitysystem.cache.CacheInvalidationDelivery;
import com.limitactivitysystem.cache.CacheInvalidationRecorder;
import com.limitactivitysystem.cache.RedisCacheStore;
import com.limitactivitysystem.cache.RedisRequestDeduplicator;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={JwtTestTokens.SECRET_PROPERTY,"spring.flyway.enabled=false","product-order.expiration.enabled=false"})
@AutoConfigureMockMvc
@Import(ProductCatalogMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="DB_PASSWORD",matches=".+")
class ProductCatalogMySqlIntegrationTest extends PaymentResourceTestSupport {
    private static final String REDIS_KEY_PREFIX="las:test:product-catalog:"+UUID.randomUUID()+":";
    @Autowired ProductQualificationService qualification;
    @Autowired ProductManagementService management;
    @Autowired SchemaObservation schema;
    @Autowired FailureObservation failures;
    @Autowired com.limitactivitysystem.product.mapper.ProductOrderExpirationMapper productOrderExpirationMapper;
    @Autowired ProductOrderClosureService productOrderClosing;
    @Autowired CacheAsideService cacheAside;
    @Autowired ActivityQueryService activityQueries;
    @Autowired ProductQueryService productQueries;
    @Autowired MeterRegistry meterRegistry;
    @Autowired CacheInvalidationRecorder cacheInvalidations;
    @Autowired StringRedisTemplate redis;
    @Value("${cache.redis.key-prefix:}") String redisKeyPrefix;
    @Autowired TransactionTemplate transactions;
    @Autowired RedisRequestDeduplicator paymentDeduplicator;
    @Autowired CacheInvalidationDelivery invalidationDelivery;
    @MockitoSpyBean RedisCacheStore redisCacheStore;
    private Activity paid,other,free;
    private ActivitySession first,second,otherSession,freeSession;
    private SessionZone paidZone,giftZone;
    private boolean corrupt;
    private String expectedProductOrderViolation;
    private Long expectedProductOrderViolationResourceId;
    private static final Map<String,Object> AUDIT=new ConcurrentSkipListMap<>();
    private static final Map<String,Object> AFTER_SALE_AUDIT=new ConcurrentSkipListMap<>();
    @DynamicPropertySource static void configureRedisNamespace(DynamicPropertyRegistry registry){
        registry.add("cache.redis.key-prefix",()->REDIS_KEY_PREFIX);
    }
    @BeforeAll static void reset(){AUDIT.clear();AFTER_SALE_AUDIT.clear();}
    @AfterAll static void save() throws Exception {
        var path=Path.of("target","verification","day16-product-order-reconciliation.json");Files.createDirectories(path.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("script","tests/sql/product_invariants.sql","cases",AUDIT.size(),"checks",AUDIT));
        var afterSalePath=Path.of("target","verification","day17-product-after-sale-reconciliation.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(afterSalePath.toFile(),
                Map.of("script","tests/sql/product_after_sale_invariants.sql","cases",AFTER_SALE_AUDIT.size(),"checks",AFTER_SALE_AUDIT));
    }
    @AfterAll void clearRedisNamespace(){
        try {var keys=redis.keys(redisKeyPrefix+"*");if(keys!=null&&!keys.isEmpty()){redis.delete(keys);}}
        catch(DataAccessException unavailable){/* Redis is optional for the non-Redis integration cases. */}
    }
    @BeforeEach void createPublishedActivityScopes() throws Exception {
        corrupt=false;expectedProductOrderViolation=null;expectedProductOrderViolationResourceId=null;
        paid=activity(publisher,"PAID");first=session(paid);second=session(paid);
        paidZone=insertZone(first,"PAID",4,4);giftZone=insertZone(first,"GIFT",2,2);insertZone(second,"PAID",2,2);publish(paid,publisher).andExpect(status().isNoContent());
        other=activity(otherPublisher,"PAID");otherSession=session(other);insertZone(otherSession,"PAID",2,2);publish(other,otherPublisher).andExpect(status().isNoContent());
        free=activity(publisher,"FREE");freeSession=session(free);publish(free,publisher).andExpect(status().isNoContent());
    }
    @AfterEach void reconcileAndClearCatalog(TestInfo info) throws Exception {
        failures.point.remove();failures.beforeThrow.remove();failures.afterShare.remove();failures.beforeLock.remove();
        failures.afterGuardLock.remove();failures.beforeGuardLock.remove();failures.deadlocksRemaining.remove();
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var rows=violations();
            AUDIT.put(info.getDisplayName(),Map.of("corruptFixture",corrupt,"violations",rows.size()));
            if(corrupt){assertThat(rows).isNotEmpty();}else{
                assertThat(rows).isEmpty();RefundInvariantAssertions.assertConsistent(jdbc);FreeReservationInvariantAssertions.assertConsistent(jdbc);
                assertThat(scriptViolations("gift_invariants.sql")).isEmpty();
            }
            var afterSaleRows=afterSaleViolations();
            if(expectedProductOrderViolation==null){assertThat(afterSaleRows).isEmpty();}
            else{
                assertThat(afterSaleRows).singleElement().satisfies(row -> {
                    assertThat(row.get("violation")).isEqualTo(expectedProductOrderViolation);
                    assertThat(((Number)row.get("resource_id")).longValue()).isEqualTo(expectedProductOrderViolationResourceId);
                });
            }
            AFTER_SALE_AUDIT.put(info.getDisplayName(),Map.of("violations",afterSaleRows.size()));
        } finally {
            jdbc.update("DELETE FROM product_after_sale_item");jdbc.update("DELETE FROM product_after_sale_request");
            jdbc.update("DELETE FROM product_order_item");jdbc.update("DELETE FROM product_order");
            jdbc.update("DELETE FROM user_product_purchase");
            jdbc.update("DELETE FROM user_activity_qualification_guard");
            jdbc.update("DELETE FROM product_whitelist_member");jdbc.update("DELETE FROM sku");jdbc.update("DELETE FROM product");
            jdbc.update("DELETE FROM ticket_refund_item");jdbc.update("DELETE FROM ticket_refund_request");
            jdbc.update("DELETE FROM whitelist_member");jdbc.update("DELETE FROM whitelist_config");
            jdbc.update("DELETE FROM gift_inventory_operation_item");jdbc.update("DELETE FROM gift_inventory_operation");
            jdbc.update("DELETE FROM free_reservation");jdbc.update("DELETE FROM free_session_quota");
        }
    }
    @Test void v13CacheTaskMigrationPreservesExistingTablesAndUsesNoPhysicalForeignKeys() throws Exception {
        assertThat(schema.before).isEqualTo(schema.after);assertThat(schema.executed).isEqualTo(6);assertThat(schema.repeated).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(28);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()",Integer.class)).isZero();
        evidence("day18-redis-cache-invalidation-migration",Map.of("beforeVersion",7,"afterVersion",13,"newTables",11,"businessTables",28,"existingTableDefinitionsUnchanged",17,"physicalForeignKeys",0,"repeatedMigrations",0));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void redisStringTemplateRoundTripsAndExpiresTtlKey() throws Exception {
        String key=redisKey("las:test:ttl:redis-string-template");
        redis.delete(key);
        assertThat(redis.hasKey(key)).isFalse();
        redis.opsForValue().set(key,"expected-value",java.time.Duration.ofMillis(700));
        assertThat(redis.opsForValue().get(key)).isEqualTo("expected-value");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime()<deadline){TimeUnit.MILLISECONDS.sleep(40);}
        assertThat(redis.hasKey(key)).isFalse();
        evidence("day18-redis-string-ttl",Map.of("fixture",Map.of("label","string-template-ttl","key",key),
                "redisBefore","absent","redisValueImmediatelyAfterSet","expected-value","ttlMillis",700,
                "redisAfterExpiry","absent"));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void publishedActivityCacheMissLoadsMysqlAndNextReadHitsRedis() throws Exception {
        Long activityId=paid.getActivityId();String identity="las:activity:"+activityId;
        redis.delete(redisKey(identity));
        var mysqlBefore=jdbc.queryForMap("SELECT activity_id,name,status FROM activity WHERE activity_id=?",activityId);
        double missesBefore=metricCount("cache.miss","activity"),fallbacksBefore=metricCount("cache.db_fallback","activity"),hitsBefore=metricCount("cache.hit","activity");

        var first=activityQueries.getPublished(activityId);
        jdbc.update("UPDATE activity SET name=? WHERE activity_id=?","changed-without-invalidation",activityId);
        var second=activityQueries.getPublished(activityId);
        var mysqlAfter=jdbc.queryForMap("SELECT activity_id,name,status FROM activity WHERE activity_id=?",activityId);

        assertThat(first.name()).isEqualTo(mysqlBefore.get("name"));
        assertThat(second.name()).isEqualTo(first.name());
        assertThat(second.name()).isNotEqualTo(mysqlAfter.get("name"));
        assertThat(redis.opsForValue().get(redisKey(identity))).contains(first.name());
        assertThat(metricCount("cache.miss","activity")-missesBefore).isEqualTo(1);
        assertThat(metricCount("cache.db_fallback","activity")-fallbacksBefore).isEqualTo(1);
        assertThat(metricCount("cache.hit","activity")-hitsBefore).isEqualTo(1);
        evidence("day18-redis-activity-miss-hit",Map.of("fixture",Map.of("label","published-paid-activity","activityId",activityId),
                "mysqlBefore",mysqlBefore,"mysqlAfter",mysqlAfter,"redisBefore","absent","redisAfter",redis.opsForValue().get(redisKey(identity)),
                "firstRead","MISS then MySQL","secondRead","HIT","mysqlValueChangedBetweenReads",true));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void missingActivityUsesShortNegativeCacheAndReloadsAfterExpiry() throws Exception {
        Long missingId=Long.MAX_VALUE;String identity="las:activity:"+missingId;redis.delete(redisKey(identity));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity WHERE activity_id=?",Integer.class,missingId)).isZero();
        double missesBefore=metricCount("cache.miss","activity"),fallbacksBefore=metricCount("cache.db_fallback","activity"),hitsBefore=metricCount("cache.hit","activity");

        assertThatThrownBy(()->activityQueries.getPublished(missingId)).isInstanceOf(com.limitactivitysystem.activity.exception.ActivityException.class);
        Long ttl=redis.getExpire(redisKey(identity),TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isBetween(2500L,5000L);
        assertThatThrownBy(()->activityQueries.getPublished(missingId)).isInstanceOf(com.limitactivitysystem.activity.exception.ActivityException.class);
        assertThat(metricCount("cache.hit","activity")-hitsBefore).isEqualTo(1);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(Boolean.TRUE.equals(redis.hasKey(redisKey(identity)))&&System.nanoTime()<deadline){TimeUnit.MILLISECONDS.sleep(25);}
        assertThat(redis.hasKey(redisKey(identity))).isFalse();
        assertThatThrownBy(()->activityQueries.getPublished(missingId)).isInstanceOf(com.limitactivitysystem.activity.exception.ActivityException.class);
        assertThat(metricCount("cache.miss","activity")-missesBefore).isEqualTo(2);
        assertThat(metricCount("cache.db_fallback","activity")-fallbacksBefore).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity WHERE activity_id=?",Integer.class,missingId)).isZero();
        evidence("day18-redis-negative-cache-expiry",Map.of("fixture",Map.of("label","missing-activity","activityId",missingId),
                "mysqlRowsBefore",0,"mysqlRowsAfter",0,"redisTtlMillisAtWrite",ttl,"redisAfterExpiry",Boolean.FALSE,
                "reads","MISS, HIT, TTL expiry, MISS","missDelta",2,"hitDelta",1));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void productOffSaleCommitInvalidatesItsPreviouslyCachedPublicDetail() throws Exception {
        long id=live("PUBLIC",null,null);String identity="las:product:"+id;
        var cached=productQueries.get(id);assertThat(redis.opsForValue().get(redisKey(identity))).isNotNull();
        var mysqlBefore=jdbc.queryForMap("SELECT product_id,name,status FROM product WHERE product_id=?",id);
        var taskBefore=jdbc.queryForObject("SELECT COUNT(*) FROM cache_invalidation_task WHERE cache_identity=?",Integer.class,identity);

        postJson("/publisher/products/"+id+"/off-sale",publisher,Map.of()).andExpect(status().isOk());

        var mysqlAfter=jdbc.queryForMap("SELECT product_id,name,status FROM product WHERE product_id=?",id);
        assertThat(mysqlAfter.get("status")).isEqualTo("OFF_SALE");
        assertThat(redis.opsForValue().get(redisKey(identity))).isNull();
        String redisAfterInvalidation="absent";
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cache_invalidation_task WHERE cache_identity=? AND completed_at IS NOT NULL",Integer.class,identity))
                .isGreaterThan(taskBefore);
        mvc.perform(get("/products/{id}",id)).andExpect(status().isNotFound());
        String redisAfterPublicRead=Boolean.TRUE.equals(redis.hasKey(redisKey(identity)))?"negative-cache-marker":"absent";
        evidence("day18-redis-product-commit-invalidation",Map.of("fixture",Map.of("label","published-public-product","productId",id),
                "mysqlBefore",mysqlBefore,"mysqlAfter",mysqlAfter,"redisBefore",cached.name(),
                "redisAfterInvalidation",redisAfterInvalidation,"redisAfterPublicRead",redisAfterPublicRead,
                "completedInvalidationTasks",jdbc.queryForObject("SELECT COUNT(*) FROM cache_invalidation_task WHERE cache_identity=? AND completed_at IS NOT NULL",Integer.class,identity)));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void productRollbackNeverCachesTheUncommittedName() throws Exception {
        long id=live("PUBLIC",null,null);String identity="las:product:"+id;redis.delete(redisKey(identity));
        String committedName=jdbc.queryForObject("SELECT name FROM product WHERE product_id=?",String.class,id);
        var cached=transactions.execute(status->{
            jdbc.update("UPDATE product SET name=? WHERE product_id=?","uncommitted-product-name",id);
            cacheInvalidations.record(identity);
            var result=productQueries.get(id);
            status.setRollbackOnly();
            return result;
        });

        assertThat(cached).isNotNull();assertThat(cached.name()).isEqualTo(committedName);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE product_id=?",String.class,id)).isEqualTo(committedName);
        String cacheAfterRollbackBeforeReload=redis.opsForValue().get(redisKey(identity));
        assertThat(cacheAfterRollbackBeforeReload).isNull();
        assertThat(productQueries.get(id).name()).isEqualTo(committedName);
        evidence("day18-redis-product-rollback",Map.of("fixture",Map.of("label","published-product","productId",id),
                "mysqlNameBefore",committedName,"mysqlNameAfterRollback",jdbc.queryForObject("SELECT name FROM product WHERE product_id=?",String.class,id),
                "redisAfterRollbackBeforeReload",cacheAfterRollbackBeforeReload==null?"absent":"present",
                "redisAfterCommittedReload",redis.opsForValue().get(redisKey(identity)),"uncommittedNameCached",false));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void paymentDedupeCompetitionAllowsOneLiveOwner() throws Exception {
        long orderId=1801001L;Long requesterId=9012L;
        int contenders=12;CountDownLatch start=new CountDownLatch(1),entered=new CountDownLatch(contenders),release=new CountDownLatch(1);
        AtomicInteger winners=new AtomicInteger();var executor=Executors.newFixedThreadPool(contenders);String key="las:dedupe:pay:"+orderId+":"+requesterId;
        redis.delete(redisKey(key));
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<contenders;i++)results.add(executor.submit(()->{
                if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Start gate timed out");
                try(var lease=paymentDeduplicator.beginPayment(orderId,requesterId)){
                    if(!lease.inProgress())winners.incrementAndGet();entered.countDown();
                    if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Release gate timed out");
                    return lease.inProgress();
                }
            }));
            start.countDown();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(winners.get()).isEqualTo(1);assertThat(redis.hasKey(redisKey(key))).isTrue();release.countDown();
            for(Future<Boolean> result:results)result.get(5,TimeUnit.SECONDS);
            assertThat(redis.hasKey(redisKey(key))).isFalse();
        } finally {release.countDown();executor.shutdownNow();}
        evidence("day18-redis-dedupe-contention",Map.of("fixture",Map.of("label","same-order-and-requester","orderId",orderId,"requesterId",requesterId),
                "contenders",contenders,"owners",winners.get(),"redisKeyAfterRelease",Boolean.FALSE));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void expiredDedupeOwnerCannotReleaseTheNextOwnersKey() throws Exception {
        long orderId=1801002L;Long requesterId=9012L;
        String key="las:dedupe:pay:"+orderId+":"+requesterId;
        redis.delete(redisKey(key));
        var oldOwner=paymentDeduplicator.beginPayment(orderId,requesterId);
        assertThat(oldOwner.inProgress()).isFalse();
        try {
            assertThat(redis.expire(redisKey(key),java.time.Duration.ZERO)).isTrue();
            try(var newOwner=paymentDeduplicator.beginPayment(orderId,requesterId)){
                assertThat(newOwner.inProgress()).isFalse();
                oldOwner.close();
                assertThat(redis.hasKey(redisKey(key))).isTrue();
                newOwner.close();
                assertThat(redis.hasKey(redisKey(key))).isFalse();
            }
        } finally {oldOwner.close();redis.delete(redisKey(key));}
        evidence("day18-redis-dedupe-owner-release",Map.of("fixture",Map.of("label","expired-owner-replaced","orderId",orderId,"requesterId",requesterId),
                "newOwnerSurvivedOldRelease",true,"keyAfterNewOwnerRelease",false));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void productReadFallsBackToRealMysqlWhenRedisAccessFails() throws Exception {
        long id=live("PUBLIC",null,null);String identity="las:product:"+id;redis.delete(redisKey(identity));
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("simulated Redis outage"))
                .when(redisCacheStore).read(identity);
        double fallbacksBefore=metricCount("cache.db_fallback","product");

        assertThat(productQueries.get(id).productId()).isEqualTo(id);

        assertThat(metricCount("cache.db_fallback","product")-fallbacksBefore).isEqualTo(1);
        assertThat(redis.opsForValue().get(redisKey(identity))).isNull();
        org.mockito.Mockito.reset(redisCacheStore);
        evidence("day18-redis-read-fallback",Map.of("fixture",Map.of("label","published-public-product","productId",id),
                "redisRead","injected connection failure","mysqlProductId",id,"fallbackDelta",1,"redisCacheAfterRead","absent"));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void cacheGenerationPreventsOldMissValueFromBeingRefilledAfterCommit() throws Exception {
        Long activityId=paid.getActivityId();
        String identity="las:activity:"+activityId;
        String mysqlBefore=jdbc.queryForObject("SELECT name FROM activity WHERE activity_id=?",String.class,activityId);
        assertThat(redis.opsForValue().get(redisKey(identity))).isNull();
        CountDownLatch mysqlReadOldValue=new CountDownLatch(1);
        CountDownLatch allowCacheFill=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            Future<String> oldRead=executor.submit(() -> cacheAside.get(identity,String.class,()->{
                String oldName=jdbc.queryForObject("SELECT name FROM activity WHERE activity_id=?",String.class,activityId);
                mysqlReadOldValue.countDown();
                try {
                    if(!allowCacheFill.await(10,TimeUnit.SECONDS)){throw new IllegalStateException("Timed out waiting to resume cache fill");}
                } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return oldName;
            }));
            assertThat(mysqlReadOldValue.await(5,TimeUnit.SECONDS)).isTrue();
            String oldName=jdbc.queryForObject("SELECT name FROM activity WHERE activity_id=?",String.class,activityId);
            assertThat(oldName).isEqualTo(mysqlBefore);
            transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE activity SET name=? WHERE activity_id=?","Updated display name",activityId);
                cacheInvalidations.record(identity);
            });
            allowCacheFill.countDown();
            assertThat(oldRead.get(5,TimeUnit.SECONDS)).isEqualTo(oldName);
            assertThat(redis.opsForValue().get(redisKey(identity))).isNull();
            String freshName=cacheAside.get(identity,String.class,()->jdbc.queryForObject(
                    "SELECT name FROM activity WHERE activity_id=?",String.class,activityId));
            assertThat(freshName).isEqualTo("Updated display name");
            assertThat(cacheAside.get(identity,String.class,()->"unexpected-reload")).isEqualTo("Updated display name");
            evidence("day18-redis-stale-refill-race",Map.of("fixture",Map.of("label","published-paid-activity","activityId",activityId),
                    "mysqlNameBefore",mysqlBefore,"mysqlNameAfterCommit",jdbc.queryForObject(
                            "SELECT name FROM activity WHERE activity_id=?",String.class,activityId),
                    "redisBefore","absent","redisAfterOldReadResumed","absent","redisAfterFreshRead",redis.opsForValue().get(redisKey(identity)),
                    "oldReadResult",oldRead.get(1,TimeUnit.SECONDS),"freshReadResult",freshName));
        } finally {
            allowCacheFill.countDown();executor.shutdownNow();
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void paymentDedupeUsesNxAndOnlyReleasesItsOwnToken() throws Exception {
        long orderId=1801003L;
        Long requesterId=9012L;
        String key="las:dedupe:pay:"+orderId+":"+requesterId;
        redis.delete(redisKey(key));
        try(var owner=paymentDeduplicator.beginPayment(orderId,requesterId)){
            assertThat(owner.inProgress()).isFalse();
            try(var duplicate=paymentDeduplicator.beginPayment(orderId,requesterId)){
                assertThat(duplicate.inProgress()).isTrue();
            }
            redis.opsForValue().set(redisKey(key),"later-owner",java.time.Duration.ofSeconds(10));
        }
        assertThat(redis.opsForValue().get(redisKey(key))).isEqualTo("later-owner");
        redis.delete(redisKey(key));
        evidence("day18-redis-dedupe-token-release",Map.of("fixture",Map.of("label","same-payment-key","orderId",orderId,"requesterId",requesterId),
                "redisBefore","absent","duplicateRejected",true,"redisAfterOldOwnerRelease","later-owner","redisAfterCleanup","absent"));
    }

    @Test @EnabledIfEnvironmentVariable(named="REDIS_TEST_ENABLED",matches="true")
    void failedCacheInvalidationStaysDurableAndScannerRetriesIt() throws Exception {
        String identity="las:test:repair:failed-delete-fixture";
        String generationKey="las:cache-generation:"+identity;
        redis.delete(redisKey(identity));redis.delete(redisKey(generationKey));
        jdbc.update("DELETE FROM cache_invalidation_task WHERE cache_identity=?",identity);
        jdbc.update("DELETE FROM cache_generation WHERE cache_identity=?",identity);
        double failuresBefore=metricCount("cache.invalidation_failure","other");
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisSystemException(
                "simulated Redis failure",new IllegalStateException("offline")))
                .when(redisCacheStore).invalidate(identity,1L);
        transactions.executeWithoutResult(status -> cacheInvalidations.record(identity));

        var pending=jdbc.queryForMap("SELECT attempts, completed_at FROM cache_invalidation_task WHERE cache_identity=?",identity);
        assertThat(pending.get("attempts")).isEqualTo(1);
        assertThat(pending.get("completed_at")).isNull();

        org.mockito.Mockito.reset(redisCacheStore);
        jdbc.update("UPDATE cache_invalidation_task SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE cache_identity=?",identity);
        invalidationDelivery.retryPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cache_invalidation_task WHERE cache_identity=? AND completed_at IS NOT NULL",
                Integer.class,identity)).isEqualTo(1);
        assertThat(metricCount("cache.invalidation_failure","other")-failuresBefore).isEqualTo(1);
        evidence("day18-redis-invalidation-retry",Map.of("fixture",Map.of("label","failed-delete-repair","cacheIdentity",identity),
                "mysqlAfterFailure",pending,"mysqlAfterRetry",jdbc.queryForMap(
                        "SELECT attempts, completed_at FROM cache_invalidation_task WHERE cache_identity=?",identity),
                "redisBefore","generation absent and cache absent","redisGenerationAfterRetry",redis.opsForValue().get(redisKey(generationKey)),
                "redisCacheAfterRetry",Boolean.TRUE.equals(redis.hasKey(redisKey(identity)))?"present":"absent"));
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
    @Test void p01_singleSkuPreviewSucceedsWithoutWritingDatabaseResources() throws Exception {
        long id=product(body("PUBLIC",null,null));long sku=sku(id,"SINGLE",4).path("skuId").asLong();publishProduct(id);
        var before=snapshot();
        postJson("/product-orders/preview",buyer,Map.of("items",List.of(Map.of("skuId",sku,"quantity",1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalAmount").value(99.0)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("AVAILABLE"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_product_purchase",Integer.class)).isZero();
    }

    @Test void p02_multipleSkuPreviewSucceedsWithoutWritingDatabaseResources() throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);
        long id=product(productBody);long firstSku=sku(id,"FIRST",4).path("skuId").asLong();
        long secondSku=sku(id,"SECOND",5).path("skuId").asLong();publishProduct(id);var before=snapshot();
        postJson("/product-orders/preview",buyer,Map.of("items",List.of(
                Map.of("skuId",firstSku,"quantity",1),Map.of("skuId",secondSku,"quantity",2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalAmount").value(297.0)).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[1].status").value("AVAILABLE"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void p03_unknownSkuIsRejectedWithoutCreatingAnOrder() throws Exception {
        var before=snapshot();
        postJson("/product-orders/preview",buyer,Map.of("items",List.of(Map.of("skuId",Long.MAX_VALUE,"quantity",1))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SKU_NOT_FOUND"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order",Integer.class)).isZero();
    }

    @Test void p04_previewReportsExactOutOfStockSkuAndDoesNotWriteResources() throws Exception {
        var request=body("PUBLIC",null,null);request.put("purchaseLimit",10);long id=product(request);
        long black=sku(id,"BLACK-M",0).path("skuId").asLong();
        long white=sku(id,"WHITE-L",20).path("skuId").asLong();publishProduct(id);
        var before=snapshot();
        postJson("/product-orders/preview",buyer,Map.of("items",List.of(
                Map.of("skuId",black,"quantity",1),Map.of("skuId",white,"quantity",2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.items[0].skuId").value(black))
                .andExpect(jsonPath("$.items[0].requestedQuantity").value(1))
                .andExpect(jsonPath("$.items[0].availableStock").value(0))
                .andExpect(jsonPath("$.items[0].status").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.items[1].skuId").value(white))
                .andExpect(jsonPath("$.items[1].availableStock").value(20))
                .andExpect(jsonPath("$.items[1].status").value("AVAILABLE"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_product_purchase",Integer.class)).isZero();
        evidence("day15-product-preview-no-reservation",Map.of("outOfStockSku",black,"availableSku",white,"inventoryAndQuotaUnchanged",true));
    }

    @Test void p06_unqualifiedBuyerCannotConfirmAnOrder() throws Exception {
        long productId=live("TICKET_REQUIRED","ACTIVITY",null);
        long skuId=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,productId);
        var before=snapshot();
        postJson("/product-orders/confirm",buyer,Map.of("requestId","p06-no-ticket","items",List.of(Map.of("skuId",skuId,"quantity",1))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PRODUCT_QUALIFICATION_DENIED"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order",Integer.class)).isZero();
    }

    @Test void p07_p08_p09_p10_p11_orderCreationIsIdempotentAndPreservesSnapshotsAndResources() throws Exception {
        var firstBody=body("PUBLIC",null,null);firstBody.put("purchaseLimit",10);
        long firstProduct=product(firstBody);
        long firstSku=sku(firstProduct,"FIRST",5).path("skuId").asLong();
        long additionalSku=sku(firstProduct,"FIRST-ALT",5).path("skuId").asLong();publishProduct(firstProduct);
        Activity anotherActivity=activity(publisher,"PAID");ActivitySession anotherSession=session(anotherActivity);
        insertZone(anotherSession,"PAID",2,2);publish(anotherActivity,publisher).andExpect(status().isNoContent());
        var secondBody=body("PUBLIC",null,null);secondBody.put("activityId",anotherActivity.getActivityId());secondBody.put("purchaseLimit",10);
        long secondProduct=product(secondBody);long secondSku=sku(secondProduct,"SECOND",5).path("skuId").asLong();publishProduct(secondProduct);
        payOrder("product-cross-activity-existing-purchase",List.of(Map.of("skuId",firstSku,"quantity",1)));
        Map<String,Object> request=Map.of("requestId","product-cross-activity","items",List.of(
                Map.of("skuId",firstSku,"quantity",1),Map.of("skuId",additionalSku,"quantity",2),Map.of("skuId",secondSku,"quantity",1)));
        var beforeStock=jdbc.queryForList("SELECT sku_id,remains_stock FROM sku ORDER BY sku_id");
        var beforeQuota=jdbc.queryForList("SELECT user_id,product_id,valid_count FROM user_product_purchase ORDER BY user_id,product_id");
        LocalDateTime requestStartedAt=now();
        var created=postJson("/product-orders/confirm",buyer,request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.publisherId").value(publisher.getId()))
                .andExpect(jsonPath("$.totalAmount").value(396.0))
                .andExpect(jsonPath("$.items.length()").value(3)).andReturn().getResponse();
        var order=objectMapper.readTree(created.getContentAsString());long orderId=order.path("orderId").asLong();
        assertThat(jdbc.queryForList("SELECT sku_id,remains_stock FROM sku ORDER BY sku_id")).isEqualTo(beforeStock);
        assertThat(jdbc.queryForList("SELECT user_id,product_id,valid_count FROM user_product_purchase ORDER BY user_id,product_id")).isEqualTo(beforeQuota);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order_item WHERE order_id=?",Integer.class,orderId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT buyer_id FROM product_order WHERE order_id=?",Long.class,orderId)).isEqualTo(buyer.getId());
        assertThat(jdbc.queryForObject("SELECT publisher_id FROM product_order WHERE order_id=?",Long.class,orderId)).isEqualTo(publisher.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT paid_at IS NULL FROM product_order WHERE order_id=?",Boolean.class,orderId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT total_amount FROM product_order WHERE order_id=?",BigDecimal.class,orderId)).isEqualByComparingTo("396.00");
        LocalDateTime createdAt=jdbc.queryForObject("SELECT created_at FROM product_order WHERE order_id=?",LocalDateTime.class,orderId);
        assertThat(createdAt).isBetween(requestStartedAt,now());
        assertThat(jdbc.queryForObject("SELECT expires_at=DATE_ADD(created_at,INTERVAL 15 MINUTE) FROM product_order WHERE order_id=?",Boolean.class,orderId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),firstProduct)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,created_at,expires_at) FROM product_order WHERE order_id=?",Integer.class,orderId)).isEqualTo(900);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM product_order_item WHERE order_id=? AND sku_id=?",BigDecimal.class,orderId,firstSku)).isEqualByComparingTo("99.00");
        jdbc.update("UPDATE sku SET price=109.00 WHERE sku_id=?",firstSku);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM product_order_item WHERE order_id=? AND sku_id=?",BigDecimal.class,orderId,firstSku)).isEqualByComparingTo("99.00");
        postJson("/product-orders/confirm",buyer,request).andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.totalAmount").value(396.0));
        postJson("/product-orders/confirm",buyer,Map.of("requestId","product-cross-activity","items",List.of(
                Map.of("skuId",firstSku,"quantity",2),Map.of("skuId",additionalSku,"quantity",2),Map.of("skuId",secondSku,"quantity",1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        evidence("day15-product-order-idempotency",Map.ofEntries(
                Map.entry("orderId",orderId),Map.entry("publisherId",publisher.getId()),Map.entry("crossActivity",true),
                Map.entry("productCount",2),Map.entry("skuCount",3),Map.entry("expiresInSeconds",900),
                Map.entry("buyerIdFromAuthentication",true),Map.entry("paidAtNull",true),Map.entry("stockUnchanged",true),
                Map.entry("productQuotaTableUnchanged",true),Map.entry("priceSnapshotUnaffectedByPriceChange",true),
                Map.entry("repeatReturnsOriginal",true)));
    }

    @Test void p05_publisherMismatchAndRelatedOrderRejectionsAreAtomic() throws Exception {
        long ownProduct=live("PUBLIC",null,null);long ownSku=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,ownProduct);
        var foreignBody=body("PUBLIC",null,null);foreignBody.put("activityId",other.getActivityId());
        var foreignCreated=postJson("/publisher/products",otherPublisher,foreignBody).andExpect(status().isCreated()).andReturn().getResponse();
        long foreignProduct=objectMapper.readTree(foreignCreated.getContentAsString()).path("productId").asLong();
        long foreignSku=objectMapper.readTree(postJson("/publisher/products/"+foreignProduct+"/skus",otherPublisher,
                Map.of("name","FOREIGN","price",99,"stock",4)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("skuId").asLong();
        postJson("/publisher/products/"+foreignProduct+"/publish",otherPublisher,Map.of()).andExpect(status().isOk());
        postJson("/product-orders/confirm",buyer,Map.of("requestId","duplicate-sku","items",List.of(
                Map.of("skuId",ownSku,"quantity",1),Map.of("skuId",ownSku,"quantity",1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_SKU"));
        var beforeCrossPublisher=snapshot();
        postJson("/product-orders/preview",buyer,Map.of("items",List.of(
                Map.of("skuId",ownSku,"quantity",1),Map.of("skuId",foreignSku,"quantity",1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_PUBLISHER_MISMATCH"));
        postJson("/product-orders/confirm",buyer,Map.of("requestId","p05-cross-publisher","items",List.of(
                Map.of("skuId",ownSku,"quantity",1),Map.of("skuId",foreignSku,"quantity",1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_PUBLISHER_MISMATCH"));
        assertThat(snapshot()).isEqualTo(beforeCrossPublisher);
        long noStockProduct=product(body("PUBLIC",null,null));long noStockSku=sku(noStockProduct,"ZERO",0).path("skuId").asLong();publishProduct(noStockProduct);
        postJson("/product-orders/confirm",buyer,Map.of("requestId","no-stock","items",List.of(Map.of("skuId",noStockSku,"quantity",1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_SKU_STOCK"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order",Integer.class)).isZero();
    }

    @Test void p13_forgedBuyerIdCannotChangeTheAuthenticatedBuyer() throws Exception {
        long productId=live("PUBLIC",null,null);long skuId=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,productId);
        var forged=new HashMap<String,Object>();forged.put("requestId","p13-forged-buyer");
        forged.put("buyerId",otherPublisher.getId());forged.put("items",List.of(Map.of("skuId",skuId,"quantity",1)));
        var before=snapshot();
        postJson("/product-orders/confirm",buyer,forged).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
        postJson("/product-orders/confirm",buyer,Map.of("requestId","p13-authenticated-buyer","items",List.of(Map.of("skuId",skuId,"quantity",1))))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT buyer_id FROM product_order WHERE request_id='p13-authenticated-buyer'",Long.class)).isEqualTo(buyer.getId());
    }

    @Test void p14_forgedTotalAmountCannotOverrideTheServerCalculatedAmount() throws Exception {
        long productId=live("PUBLIC",null,null);long skuId=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,productId);
        var forged=new HashMap<String,Object>();forged.put("requestId","p14-forged-total");forged.put("totalAmount",new BigDecimal("0.01"));
        forged.put("items",List.of(Map.of("skuId",skuId,"quantity",1)));
        var before=snapshot();
        postJson("/product-orders/confirm",buyer,forged).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
        postJson("/product-orders/confirm",buyer,Map.of("requestId","p14-server-total","items",List.of(Map.of("skuId",skuId,"quantity",1))))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT total_amount FROM product_order WHERE request_id='p14-server-total'",BigDecimal.class)).isEqualByComparingTo("99.00");
    }

    @Test void specialReturnPolicyRequiresAndPersistsBuyerAcknowledgement() throws Exception {
        var specialBody=body("PUBLIC",null,null);specialBody.put("refundPolicy","SPECIAL_NO_REASON_RETURN");
        long id=product(specialBody);long specialSku=sku(id,"SPECIAL",2).path("skuId").asLong();publishProduct(id);
        postJson("/product-orders/preview",buyer,Map.of("items",List.of(Map.of("skuId",specialSku,"quantity",1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].requiresPolicyAcknowledgement").value(true));
        postJson("/product-orders/confirm",buyer,Map.of("requestId","special-no-ack","items",List.of(Map.of("skuId",specialSku,"quantity",1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRODUCT_POLICY_ACK_REQUIRED"));
        var response=postJson("/product-orders/confirm",buyer,Map.of("requestId","special-ack","items",List.of(
                Map.of("skuId",specialSku,"quantity",1,"acknowledgeSpecialPolicy",true))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long orderId=objectMapper.readTree(response.getContentAsString()).path("orderId").asLong();
        assertThat(jdbc.queryForObject("SELECT special_policy_acknowledged FROM product_order_item WHERE order_id=?",Boolean.class,orderId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT policy_acknowledged_at IS NOT NULL FROM product_order_item WHERE order_id=?",Boolean.class,orderId)).isTrue();
    }

    @Test void p12_failureAfterOrderItemsSqlRollsBackHeaderAndAllItems() throws Exception {
        long id=product(body("PUBLIC",null,null));long firstSku=sku(id,"ROLLBACK-A",3).path("skuId").asLong();
        long secondSku=sku(id,"ROLLBACK-B",3).path("skuId").asLong();publishProduct(id);
        var beforeStock=jdbc.queryForList("SELECT sku_id,remains_stock FROM sku ORDER BY sku_id");
        failures.point.set("ProductOrderMapper.insertItems");
        failures.beforeThrow.set(() -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order_item",Integer.class)).isEqualTo(2);
        });
        try {
            postJson("/product-orders/confirm",buyer,Map.of("requestId","product-rollback","items",List.of(
                    Map.of("skuId",firstSku,"quantity",1),Map.of("skuId",secondSku,"quantity",1))))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("PRODUCT_TECHNICAL_FAILURE"));
        } finally {
            failures.point.remove();failures.beforeThrow.remove();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order_item",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_product_purchase",Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT sku_id,remains_stock FROM sku ORDER BY sku_id")).isEqualTo(beforeStock);
    }

    @Test void p16_outOfStockAfterAnEarlierSkuUpdateRollsBackQuotaAndStockThenClosesTheWholeOrder() throws Exception {
        var configuration=body("PUBLIC",null,null);configuration.put("purchaseLimit",10);
        long productId=product(configuration);long firstSku=sku(productId,"A-FIRST",10).path("skuId").asLong();
        long secondSku=sku(productId,"B-SOLD-OUT",4).path("skuId").asLong();publishProduct(productId);
        long orderId=createProductOrder("p16-atomic-stock",List.of(Map.of("skuId",firstSku,"quantity",2),Map.of("skuId",secondSku,"quantity",1)));
        expectedProductOrderViolation="SKU_AVAILABLE_STOCK_MISMATCH";
        expectedProductOrderViolationResourceId=secondSku;
        // Represents stock depleted outside the paid product-order ledger.
        jdbc.update("UPDATE sku SET remains_stock=0 WHERE sku_id=?",secondSku);
        var beforeItems=jdbc.queryForList("SELECT order_item_id,sku_id,quantity,unit_price FROM product_order_item WHERE order_id=? ORDER BY sku_id",orderId);
        postJson("/product-orders/"+orderId+"/pay",buyer,Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_ORDER_OUT_OF_STOCK"))
                .andExpect(jsonPath("$.orderId").value(orderId)).andExpect(jsonPath("$.unavailableItems[0].skuId").value(secondSku))
                .andExpect(jsonPath("$.unavailableItems[0].requestedQuantity").value(1))
                .andExpect(jsonPath("$.unavailableItems[0].availableStock").value(0))
                .andExpect(jsonPath("$.unavailableItems[0].status").value("INSUFFICIENT_STOCK"));
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,firstSku)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,secondSku)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM product_order WHERE order_id=?",String.class,orderId)).isEqualTo("OUT_OF_STOCK");
        assertThat(jdbc.queryForObject("SELECT paid_at IS NULL FROM product_order WHERE order_id=?",Boolean.class,orderId)).isTrue();
        assertThat(jdbc.queryForList("SELECT order_item_id,sku_id,quantity,unit_price FROM product_order_item WHERE order_id=? ORDER BY sku_id",orderId)).isEqualTo(beforeItems);
        evidence("day16-product-out-of-stock-rollback",Map.of("orderId",orderId,"firstSkuRestored",true,"quotaRolledBack",true,"closedReason","OUT_OF_STOCK","itemsPreserved",true));
    }

    @Test void p17_productQuotaIsSharedAcrossSkusUnderConcurrentPayment() throws Exception {
        long productId=product(body("PUBLIC",null,null));long firstSku=sku(productId,"FIRST-SKU",5).path("skuId").asLong();
        long secondSku=sku(productId,"ALT-SKU",5).path("skuId").asLong();publishProduct(productId);
        long firstOrder=createProductOrder("p17-limit-a",List.of(Map.of("skuId",firstSku,"quantity",2)));
        long secondOrder=createProductOrder("p17-limit-b",List.of(Map.of("skuId",secondSku,"quantity",2)));
        var start=new CyclicBarrier(2);
        var first=async(() -> {start.await(10,TimeUnit.SECONDS);return mvc.perform(post("/product-orders/{id}/pay",firstOrder).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse().getStatus();});
        var second=async(() -> {start.await(10,TimeUnit.SECONDS);return mvc.perform(post("/product-orders/{id}/pay",secondOrder).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse().getStatus();});
        List<Integer> statuses=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        assertThat(statuses).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_order WHERE order_id IN (?,?) AND status='PAID'",Integer.class,firstOrder,secondOrder)).isEqualTo(1);
        evidence("day16-product-cross-sku-quota-race",Map.of("productId",productId,"orders",List.of(firstOrder,secondOrder),"paidOrders",1,"validCount",2));
    }

    @Test void p18_duplicateConcurrentPaymentReturnsTheSamePaidOrderWithoutRepeatingEffects() throws Exception {
        long productId=product(body("PUBLIC",null,null));long skuId=sku(productId,"PAY-ONCE",5).path("skuId").asLong();publishProduct(productId);
        long orderId=createProductOrder("p18-idempotent-pay",List.of(Map.of("skuId",skuId,"quantity",2)));
        var mysqlBefore=Map.of("skuStock",jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId),
                "purchaseRows",jdbc.queryForObject("SELECT COUNT(*) FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId),
                "orderStatus",jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId));
        var start=new CyclicBarrier(2);
        var first=async(() -> {start.await(10,TimeUnit.SECONDS);return mvc.perform(post("/product-orders/{id}/pay",orderId).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse();});
        var second=async(() -> {start.await(10,TimeUnit.SECONDS);return mvc.perform(post("/product-orders/{id}/pay",orderId).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse();});
        var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
        assertThat(List.of(a.getStatus(),b.getStatus())).containsExactlyInAnyOrder(200,200);
        assertThat(objectMapper.readTree(a.getContentAsString()).path("status").asText()).isEqualTo("PAID");
        assertThat(objectMapper.readTree(b.getContentAsString()).path("status").asText()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT paid_at IS NOT NULL FROM product_order WHERE order_id=?",Boolean.class,orderId)).isTrue();
        var mysqlAfter=Map.of("skuStock",jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId),
                "validCount",jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId),
                "paidOrders",jdbc.queryForObject("SELECT COUNT(*) FROM product_order WHERE order_id=? AND status='PAID'",Integer.class,orderId),
                "orderStatus",jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId));
        evidence("day18-redis-down-duplicate-payment",Map.of("fixture",Map.of("label","p18-idempotent-product-payment","productId",productId,"skuId",skuId,"orderId",orderId),
                "mysqlBeforePayment",mysqlBefore,"mysqlAfterTwoRequests",mysqlAfter,"httpStatuses",List.of(a.getStatus(),b.getStatus()),
                "redisStateAtEnd",redisState(),"stockDeductedOnce",true,"quotaChargedOnce",true));
    }

    @Test void p19_deadlockRetriesOnlyAfterRollbackAndCompletesOnce() throws Exception {
        long productId=live("PUBLIC",null,null);long skuId=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,productId);
        long orderId=createProductOrder("p19-deadlock-retry",List.of(Map.of("skuId",skuId,"quantity",1)));
        failures.deadlocksRemaining.set(1);
        postJson("/product-orders/"+orderId+"/pay",buyer,Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(failures.deadlocksRemaining.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isZero();
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isEqualTo(1);
    }

    @Test void productOrderExpirationScanClosesOnlyExpiredPendingOrdersUsingDatabaseTime() throws Exception {
        long productId=live("PUBLIC",null,null);long skuId=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,productId);
        long orderId=createProductOrder("day16-expiration-scan",List.of(Map.of("skuId",skuId,"quantity",1)));
        jdbc.update("UPDATE product_order SET created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 16 MINUTE) WHERE order_id=?",orderId);
        new com.limitactivitysystem.product.schedule.ProductOrderExpirationJob(productOrderExpirationMapper,productOrderClosing,10).closeExpiredOrders();
        assertThat(jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM product_order WHERE order_id=?",String.class,orderId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT paid_at IS NULL FROM product_order WHERE order_id=?",Boolean.class,orderId)).isTrue();
    }

    @Test void p21_multiProductMultiSkuPaymentUsesPersistedPricesAndAggregatesEachProduct() throws Exception {
        long firstProduct=product(body("PUBLIC",null,null));long firstSku=sku(firstProduct,"main",1).path("skuId").asLong();
        long alternateSku=sku(firstProduct,"SECOND-SIZE",4).path("skuId").asLong();
        publishProduct(firstProduct);
        long secondProduct=live("PUBLIC",null,null);long thirdSku=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,secondProduct);
        long orderId=createProductOrder("p21-snapshot-payment",List.of(Map.of("skuId",firstSku,"quantity",1),
                Map.of("skuId",alternateSku,"quantity",1),Map.of("skuId",thirdSku,"quantity",1)));
        jdbc.update("UPDATE sku SET price=109.00 WHERE sku_id=?",firstSku);
        postJson("/product-orders/"+orderId+"/pay",buyer,Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.totalAmount").value(297.00))
                .andExpect(jsonPath("$.items[0].unitPrice").value(99.00));
        assertThat(jdbc.queryForObject("SELECT unit_price FROM product_order_item WHERE order_id=? AND sku_id=?",BigDecimal.class,orderId,firstSku)).isEqualByComparingTo("99.00");
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,firstSku)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,alternateSku)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,thirdSku)).isZero();
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),firstProduct)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),secondProduct)).isEqualTo(1);
    }

    @Test void ticketRefundThatGetsTheGuardFirstMakesLaterProductPaymentRecheckAndReject() throws Exception {
        payTickets(first,paidZone,"p20-refund-first-ticket");
        long ticketId=jdbc.queryForObject("SELECT ticket_id FROM ticket WHERE status='VALID' ORDER BY ticket_id LIMIT 1",Long.class);
        long productId=live("TICKET_REQUIRED","SESSION",first.getActivitySessionId());long skuId=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,productId);
        long productOrder=createProductOrder("p20-refund-first-order",List.of(Map.of("skuId",skuId,"quantity",1)));
        long refundId=createRefundRequest(ticketId,"p20-refund-first");
        var held=gate();var release=gate();var paymentAttempting=gate();var refundConnection=new java.util.concurrent.atomic.AtomicLong();var paymentConnection=new java.util.concurrent.atomic.AtomicLong();
        var refund=async(() -> {
            failures.afterGuardLock.set(() -> {refundConnection.set(connectionId());held.countDown();await(release);});
            try{return mvc.perform(post("/ticket-refunds/{id}/process",refundId).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse();}
            finally{failures.afterGuardLock.remove();}
        });
        await(held);
        var payment=async(() -> {
            failures.beforeGuardLock.set(() -> {paymentConnection.set(connectionId());paymentAttempting.countDown();});
            try{return mvc.perform(post("/product-orders/{id}/pay",productOrder).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse();}
            finally{failures.beforeGuardLock.remove();}
        });
        try {await(paymentAttempting);awaitDatabaseLockWait(paymentConnection.get(),refundConnection.get(),"user_activity_qualification_guard");}
        finally {release.countDown();}
        var refundResponse=refund.get(20,TimeUnit.SECONDS);var paymentResponse=payment.get(20,TimeUnit.SECONDS);
        assertThat(refundResponse.getStatus()).isEqualTo(200);assertThat(paymentResponse.getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(paymentResponse.getContentAsString()).path("code").asText()).isEqualTo("PRODUCT_QUALIFICATION_DENIED");
        assertThat(jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,productOrder)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE ticket_id=?",String.class,ticketId)).isEqualTo("REFUNDED");
        evidence("day16-refund-before-session-qualified-product-payment",Map.of("productPaymentRejected",true,"ticketRefundCommitted",true,"sessionScopeRechecked",true,"productResourcesUnchanged",true));
    }

    @Test void productPaymentThatGetsTheGuardFirstLeavesTraceableSaleForLaterTicketRefund() throws Exception {
        payTickets(first,paidZone,"p20-product-first-ticket");
        long ticketId=jdbc.queryForObject("SELECT ticket_id FROM ticket WHERE status='VALID' ORDER BY ticket_id LIMIT 1",Long.class);
        long productId=live("TICKET_REQUIRED","ACTIVITY",null);long skuId=jdbc.queryForObject("SELECT sku_id FROM sku WHERE product_id=?",Long.class,productId);
        long productOrder=createProductOrder("p20-product-first-order",List.of(Map.of("skuId",skuId,"quantity",1)));
        long refundId=createRefundRequest(ticketId,"p20-product-first");
        Long versionBefore=jdbc.queryForObject("SELECT qualification_version FROM user_activity_qualification_guard WHERE user_id=? AND activity_id=?",Long.class,buyer.getId(),paid.getActivityId());
        var held=gate();var release=gate();var refundAttempting=gate();var productConnection=new java.util.concurrent.atomic.AtomicLong();var refundConnection=new java.util.concurrent.atomic.AtomicLong();
        var payment=async(() -> {
            failures.afterGuardLock.set(() -> {productConnection.set(connectionId());held.countDown();await(release);});
            try{return mvc.perform(post("/product-orders/{id}/pay",productOrder).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse();}
            finally{failures.afterGuardLock.remove();}
        });
        await(held);
        var refund=async(() -> {
            failures.beforeGuardLock.set(() -> {refundConnection.set(connectionId());refundAttempting.countDown();});
            try{return mvc.perform(post("/ticket-refunds/{id}/process",refundId).header(HttpHeaders.AUTHORIZATION,bearer(buyer))).andReturn().getResponse();}
            finally{failures.beforeGuardLock.remove();}
        });
        try {await(refundAttempting);awaitDatabaseLockWait(refundConnection.get(),productConnection.get(),"user_activity_qualification_guard");}
        finally {release.countDown();}
        var paymentResponse=payment.get(20,TimeUnit.SECONDS);var refundResponse=refund.get(20,TimeUnit.SECONDS);
        assertThat(paymentResponse.getStatus()).isEqualTo(200);assertThat(refundResponse.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,productOrder)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT qualification_guard_version_snapshot FROM product_order_item WHERE order_id=?",Long.class,productOrder)).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT qualification_version FROM user_activity_qualification_guard WHERE user_id=? AND activity_id=?",Long.class,buyer.getId(),paid.getActivityId())).isEqualTo(versionBefore+1);
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE ticket_id=?",String.class,ticketId)).isEqualTo("REFUNDED");
        evidence("day16-product-before-refund",Map.of("productPaidBeforeRefund",true,"itemGuardVersion",versionBefore,"guardVersionAfterRefund",versionBefore+1,"automaticAfterSale",false));
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
        evictTestCacheIfAvailable("las:product:"+id);
        mvc.perform(get("/products/{id}",id)).andExpect(status().isNotFound());allowed(id,false,"PRODUCT_NOT_ON_SALE");
    }
    @Test void independentServiceQueriesRejectInactiveAccountsAndDoNotConsumeStock() throws Exception {
        long id=live("PUBLIC",null,null);var before=snapshot();assertThat(qualification.check(buyer.getId(),id).allowed()).isTrue();
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",buyer.getId());
        assertThat(qualification.check(buyer.getId(),id).reason()).isEqualTo("USER_INACTIVE");assertThat(snapshot()).isEqualTo(before);
    }

    @Test void fulfillmentIsTrackedPerOrderItemAndChecksPublisherAndBuyerOwnership() throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);
        long productId=product(productBody);long black=sku(productId,"BLACK-M",5).path("skuId").asLong();
        long white=sku(productId,"WHITE-L",5).path("skuId").asLong();publishProduct(productId);
        long orderId=payOrder("fulfillment-multi-sku",List.of(Map.of("skuId",black,"quantity",1),Map.of("skuId",white,"quantity",1)));
        long blackItem=orderItem(orderId,black),whiteItem=orderItem(orderId,white);
        postJson("/publisher/product-orders/"+orderId+"/ship",otherPublisher,Map.of("orderItemId",blackItem,"quantity",1))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PRODUCT_ORDER_ACCESS_DENIED"));
        var shipped=postJson("/publisher/product-orders/"+orderId+"/ship",publisher,Map.of("orderItemId",blackItem,"quantity",1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shippedAt").isNotEmpty()).andReturn().getResponse();
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM product_order_item WHERE order_item_id=?",String.class,whiteItem)).isEqualTo("UNSHIPPED");
        postJson("/product-orders/"+orderId+"/receive",otherPublisher,Map.of("orderItemId",blackItem,"quantity",1))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PRODUCT_ORDER_ACCESS_DENIED"));
        var received=postJson("/product-orders/"+orderId+"/receive",buyer,Map.of("orderItemId",blackItem,"quantity",1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty()).andReturn().getResponse();
        var shipJson=objectMapper.readTree(shipped.getContentAsString());var receiveJson=objectMapper.readTree(received.getContentAsString());
        assertThat(receiveJson.path("receivedAt").asText()).isGreaterThanOrEqualTo(shipJson.path("shippedAt").asText());
        postJson("/product-orders/"+orderId+"/receive",buyer,Map.of("orderItemId",blackItem,"quantity",1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_FULFILLMENT_INVALID"));
        postJson("/product-after-sales",buyer,Map.of("requestId","mixed-only-white-refund","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",whiteItem,"quantity",1,"reason","QUALITY_PROBLEM"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM product_order_item WHERE order_item_id=?",String.class,blackItem)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,blackItem)).isZero();
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM product_order_item WHERE order_item_id=?",String.class,whiteItem)).isEqualTo("UNSHIPPED");
        assertThat(jdbc.queryForObject("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,whiteItem)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,black)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,white)).isEqualTo(5);
        assertThat(afterSaleViolations()).isEmpty();
    }

    @Test void unshippedPartialRefundRestoresSkuQuotaAndReturnsTheOriginalIdempotentResult() throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);
        long productId=product(productBody);long skuId=sku(productId,"refund-unshipped",10).path("skuId").asLong();publishProduct(productId);
        long orderId=payOrder("after-sale-unshipped",List.of(Map.of("skuId",skuId,"quantity",3)));
        long itemId=orderItem(orderId,skuId);
        var mysqlBefore=Map.of("skuStock",jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId),
                "productValidCount",jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId),
                "orderStatus",jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId),
                "refundPending",jdbc.queryForObject("SELECT refund_pending_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId),
                "refunded",jdbc.queryForObject("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId));
        var input=Map.of("requestId","partial-return-2","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",2,"reason","QUALITY_PROBLEM")));
        var first=postJson("/product-after-sales",buyer,input).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.items[0].refundedQuantity").value(2))
                .andExpect(jsonPath("$.items[0].returnedQuantity").value(0)).andReturn().getResponse().getContentAsString();
        postJson("/product-after-sales",buyer,input).andExpect(status().isCreated()).andExpect(content().json(first));
        postJson("/product-after-sales",buyer,Map.of("requestId","partial-return-2","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",1,"reason","QUALITY_PROBLEM"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refund_pending_quantity+0 FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isZero();
        assertThat(jdbc.queryForObject("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(afterSaleViolations()).isEmpty();
        var mysqlAfter=Map.of("skuStock",jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId),
                "productValidCount",jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId),
                "orderStatus",jdbc.queryForObject("SELECT status FROM product_order WHERE order_id=?",String.class,orderId),
                "refundPending",jdbc.queryForObject("SELECT refund_pending_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId),
                "refunded",jdbc.queryForObject("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId));
        evidence("day18-redis-down-refund-idempotency",Map.of("fixture",Map.of("label","unshipped-partial-refund","productId",productId,"skuId",skuId,"orderId",orderId,"orderItemId",itemId),
                "mysqlBeforeRefund",mysqlBefore,"mysqlAfterDuplicateAndConflictRequests",mysqlAfter,"redisStateAtEnd",redisState(),
                "sameRequestReturnedOriginalResult",true,"changedRequestPayloadRejected",true));
    }

    @Test void concurrentAfterSaleRequestsCannotRefundMoreThanPurchased() throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);
        long productId=product(productBody);long skuId=sku(productId,"refund-race",10).path("skuId").asLong();publishProduct(productId);
        long orderId=payOrder("after-sale-race",List.of(Map.of("skuId",skuId,"quantity",3)));
        long itemId=orderItem(orderId,skuId);var start=new CountDownLatch(1);
        Callable<Integer> first=() -> {start.await(10,TimeUnit.SECONDS);return postJson("/product-after-sales",buyer,
                Map.of("requestId","race-a","orderId",orderId,"items",List.of(Map.of("orderItemId",itemId,"quantity",2,"reason","QUALITY_PROBLEM"))))
                .andReturn().getResponse().getStatus();};
        Callable<Integer> second=() -> {start.await(10,TimeUnit.SECONDS);return postJson("/product-after-sales",buyer,
                Map.of("requestId","race-b","orderId",orderId,"items",List.of(Map.of("orderItemId",itemId,"quantity",2,"reason","QUALITY_PROBLEM"))))
                .andReturn().getResponse().getStatus();};
        var a=async(first);var b=async(second);start.countDown();List<Integer> statuses=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        assertThat(statuses).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT refunded_quantity+refund_pending_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isEqualTo(1);
        assertThat(afterSaleViolations()).isEmpty();
    }

    @Test void shippingAndUnshippedRefundCannotBothSucceed() throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);
        long productId=product(productBody);long skuId=sku(productId,"ship-refund-race",5).path("skuId").asLong();publishProduct(productId);
        long orderId=payOrder("ship-refund-race-order",List.of(Map.of("skuId",skuId,"quantity",1)));
        long itemId=orderItem(orderId,skuId);var start=new CountDownLatch(1);
        Callable<Integer> ship=() -> {start.await(10,TimeUnit.SECONDS);return postJson("/publisher/product-orders/"+orderId+"/ship",publisher,
                Map.of("orderItemId",itemId,"quantity",1)).andReturn().getResponse().getStatus();};
        Callable<Integer> refund=() -> {start.await(10,TimeUnit.SECONDS);return postJson("/product-after-sales",buyer,
                Map.of("requestId","ship-refund-race","orderId",orderId,"items",List.of(Map.of(
                        "orderItemId",itemId,"quantity",1,"reason","QUALITY_PROBLEM"))))
                .andReturn().getResponse().getStatus();};
        var shipped=async(ship);var refunded=async(refund);start.countDown();
        int shipStatus=shipped.get(15,TimeUnit.SECONDS),refundStatus=refunded.get(15,TimeUnit.SECONDS);
        assertThat((shipStatus==200 && refundStatus==409) || (shipStatus==409 && refundStatus==201)).isTrue();
        var line=jdbc.queryForMap("SELECT fulfillment_status,refunded_quantity,refund_pending_quantity FROM product_order_item WHERE order_item_id=?",itemId);
        assertThat(("SHIPPED".equals(line.get("fulfillment_status")) && ((Number)line.get("refunded_quantity")).intValue()==0)
                || ("UNSHIPPED".equals(line.get("fulfillment_status")) && ((Number)line.get("refunded_quantity")).intValue()==1)).isTrue();
        assertThat(((Number)line.get("refund_pending_quantity")).intValue()).isZero();
        if(shipStatus==200){
            assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isEqualTo(1);
        }else{
            assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isZero();
        }
        assertThat(afterSaleViolations()).isEmpty();
    }

    @Test void specialTemplateBlocksOnlyNoReasonAndNonresalableReturnedGoodsDoNotRestoreStock() throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);productBody.put("refundPolicy","SPECIAL_NO_REASON_RETURN");
        long productId=product(productBody);long skuId=sku(productId,"special-return",5).path("skuId").asLong();publishProduct(productId);
        var orderResponse=postJson("/product-orders/confirm",buyer,Map.of("requestId","special-return-order","items",List.of(Map.of(
                "skuId",skuId,"quantity",3,"acknowledgeSpecialPolicy",true)))).andExpect(status().isCreated()).andReturn().getResponse();
        long orderId=objectMapper.readTree(orderResponse.getContentAsString()).path("orderId").asLong();
        postJson("/product-orders/"+orderId+"/pay",buyer,Map.of()).andExpect(status().isOk());long itemId=orderItem(orderId,skuId);
        postJson("/publisher/product-orders/"+orderId+"/ship",publisher,Map.of("orderItemId",itemId,"quantity",3)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(2);
        postJson("/product-after-sales",buyer,Map.of("requestId","special-no-reason","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",1,"reason","NO_REASON"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_AFTER_SALE_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(2);
        postJson("/product-orders/"+orderId+"/receive",buyer,Map.of("orderItemId",itemId,"quantity",3)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(2);
        postJson("/product-after-sales",buyer,Map.of("requestId","special-quality","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",1,"reason","QUALITY_PROBLEM","simulateInspectionPassed",false,"simulateResalable",false))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].inspectionResult").value("FAILED"))
                .andExpect(jsonPath("$.items[0].returnedQuantity").value(1))
                .andExpect(jsonPath("$.items[0].returnedAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(2);
        postJson("/product-after-sales",buyer,Map.of("requestId","special-passed-not-resalable","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",1,"reason","QUALITY_PROBLEM","simulateInspectionPassed",true,"simulateResalable",false))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.items[0].inspectionResult").value("PASSED"))
                .andExpect(jsonPath("$.items[0].resalable").value(false));
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(2);
        postJson("/product-after-sales",buyer,Map.of("requestId","special-passed-resalable","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",1,"reason","QUALITY_PROBLEM"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.items[0].inspectionResult").value("PASSED"))
                .andExpect(jsonPath("$.items[0].resalable").value(true));
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT returned_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT restocked_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isZero();
        assertThat(afterSaleViolations()).isEmpty();
    }

    @Test void standardNoReasonUsesServerRecordedReceiptTime() throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);
        long productId=product(productBody);long skuId=sku(productId,"standard-return",5).path("skuId").asLong();publishProduct(productId);
        long orderId=payOrder("standard-return-order",List.of(Map.of("skuId",skuId,"quantity",1)));long itemId=orderItem(orderId,skuId);
        postJson("/publisher/product-orders/"+orderId+"/ship",publisher,Map.of("orderItemId",itemId,"quantity",1)).andExpect(status().isOk());
        postJson("/product-orders/"+orderId+"/receive",buyer,Map.of("orderItemId",itemId,"quantity",1)).andExpect(status().isOk());
        jdbc.update("UPDATE product_order_item SET shipped_at=CURRENT_TIMESTAMP(3)-INTERVAL 6 DAY, "
                + "received_at=CURRENT_TIMESTAMP(3)-INTERVAL 6 DAY WHERE order_item_id=?",itemId);
        postJson("/product-after-sales",buyer,Map.of("requestId","standard-no-reason","orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",1,"reason","NO_REASON"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT returned_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isEqualTo(1);
        assertThat(afterSaleViolations()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"ProductAfterSaleMapper.markItemSucceeded","UserProductPurchaseMapper.tryDecrease","SkuMapper.restoreStock"})
    void afterSaleFailuresAreQueryableAndCanBeSafelyRetried(String point) throws Exception {
        var productBody=body("PUBLIC",null,null);productBody.put("purchaseLimit",10);
        long productId=product(productBody);long skuId=sku(productId,"refund-rollback-"+point,5).path("skuId").asLong();publishProduct(productId);
        long orderId=payOrder("after-sale-rollback-"+point,List.of(Map.of("skuId",skuId,"quantity",1)));long itemId=orderItem(orderId,skuId);
        var input=Map.of("requestId","after-sale-rollback-"+point,"orderId",orderId,"items",List.of(Map.of(
                "orderItemId",itemId,"quantity",1,"reason","QUALITY_PROBLEM")));
        failures.point.set(point);
        postJson("/product-after-sales",buyer,input).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PRODUCT_TECHNICAL_FAILURE"));
        assertThat(jdbc.queryForObject("SELECT status FROM product_after_sale_request WHERE request_id=?",String.class,input.get("requestId"))).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM product_after_sale_request WHERE request_id=?",String.class,input.get("requestId"))).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT a.status FROM product_after_sale_item a JOIN product_after_sale_request r ON r.after_sale_request_id=a.after_sale_request_id WHERE r.request_id=?",String.class,input.get("requestId"))).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refund_pending_quantity+refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isZero();
        assertThat(afterSaleViolations()).isEmpty();
        postJson("/product-after-sales",buyer,input).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT status FROM product_after_sale_request WHERE request_id=?",String.class,input.get("requestId"))).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT remains_stock FROM sku WHERE sku_id=?",Integer.class,skuId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?",Integer.class,buyer.getId(),productId)).isZero();
        assertThat(jdbc.queryForObject("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?",Integer.class,itemId)).isEqualTo(1);
        assertThat(afterSaleViolations()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"product","sku","member"})
    void logicalOrphansAreDetectedByCatalogReconciliation(String table) throws Exception {
        long id=live("PRODUCT_WHITELIST",null,null);corrupt=true;
        switch(table){case "product"->jdbc.update("UPDATE product SET activity_id=? WHERE product_id=?",Long.MAX_VALUE,id);case "sku"->jdbc.update("UPDATE sku SET product_id=? WHERE product_id=?",Long.MAX_VALUE,id);default->jdbc.update("INSERT INTO product_whitelist_member(product_id,user_id) VALUES(?,?)",id,Long.MAX_VALUE);}
        assertThat(violations()).isNotEmpty();
    }
    private double metricCount(String name,String cacheType){
        var counter=meterRegistry.find(name).tag("cache",cacheType).counter();return counter==null?0:counter.count();
    }

    private String redisState(){
        var factory=redis.getConnectionFactory();if(factory==null)return "not-configured";
        try(var connection=factory.getConnection()){connection.ping();return "available";}
        catch(DataAccessException unavailable){return "unavailable";}
    }

    private String redisKey(String identity){return redisKeyPrefix+identity;}

    private void evictTestCacheIfAvailable(String identity){
        try {redis.delete(redisKey(identity));}
        catch(DataAccessException unavailable){/* Raw SQL fixture changes have no cache event when Redis is absent. */}
    }

    private Activity activity(com.limitactivitysystem.identity.model.AppUser owner,String type){
        var a=insertDraft(owner,type);var now=now();jdbc.update("UPDATE activity SET open_time=?,close_time=? WHERE activity_id=?",now.minusDays(10),now.plusDays(1),a.getActivityId());return a;
    }
    private ActivitySession session(Activity activity){var s=insertSession(activity);jdbc.update("UPDATE activity_session SET start_time=?,end_time=?,max_tickets_per_user=? WHERE activity_session_id=?",now().plusDays(20),now().plusDays(20).plusHours(2),activity.getType().equals("PAID")?4:null,s.getActivitySessionId());return s;}
    private LocalDateTime now(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",LocalDateTime.class);}
    private Map<String,Object> body(String type,String scope,Long session){var r=new HashMap<String,Object>();r.put("activityId",paid.getActivityId());r.put("name","test-product");r.put("description","catalog test");r.put("qualificationType",type);r.put("qualificationScope",scope);r.put("qualificationSessionId",session);r.put("purchaseLimit",2);r.put("refundPolicy","STANDARD_7D");return r;}
    private long product(Map<String,Object> body) throws Exception {return objectMapper.readTree(postJson("/publisher/products",publisher,body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("productId").asLong();}
    private JsonNode sku(long id,String name,int stock) throws Exception {return objectMapper.readTree(postJson("/publisher/products/"+id+"/skus",publisher,Map.of("name",name,"price",99,"stock",stock)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
    private long createProductOrder(String requestId,List<Map<String,Object>> items) throws Exception {
        var response=postJson("/product-orders/confirm",buyer,Map.of("requestId",requestId,"items",items)).andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readTree(response.getContentAsString()).path("orderId").asLong();
    }
    private long payOrder(String requestId,List<Map<String,Object>> items) throws Exception {
        long orderId=createProductOrder(requestId,items);
        postJson("/product-orders/"+orderId+"/pay",buyer,Map.of()).andExpect(status().isOk());
        return orderId;
    }
    private long orderItem(long orderId,long skuId) {
        return jdbc.queryForObject("SELECT order_item_id FROM product_order_item WHERE order_id=? AND sku_id=?",Long.class,orderId,skuId);
    }
    private long createRefundRequest(long ticketId,String requestId) throws Exception {
        var response=postJson("/ticket-refunds",buyer,Map.of("requestId",requestId,"orderId",jdbc.queryForObject(
                "SELECT i.order_id FROM ticket t JOIN ticket_order_item i ON i.order_item_id=t.order_item_id WHERE t.ticket_id=?",Long.class,ticketId),
                "ticketIds",List.of(ticketId),"reason","USER_REQUEST")).andExpect(status().isCreated()).andReturn().getResponse();
        return objectMapper.readTree(response.getContentAsString()).path("refundId").asLong();
    }
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
    private Map<String,Object> snapshot(){var result=new LinkedHashMap<String,Object>();for(String table:List.of("product","sku","product_whitelist_member","ticket","ticket_order","ticket_order_item","user_session_purchase","user_activity_qualification_guard","product_order","product_order_item","product_after_sale_request","product_after_sale_item","user_product_purchase")){result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));}return result;}
    private List<Map<String,Object>> violations() throws Exception {return scriptViolations("product_invariants.sql");}
    private List<Map<String,Object>> afterSaleViolations() throws Exception {return scriptViolations("product_after_sale_invariants.sql");}
    private List<Map<String,Object>> scriptViolations(String name) throws Exception {var sql=Files.readString(Path.of("tests","sql",name)).lines().filter(line->!line.startsWith("--")).collect(Collectors.joining("\n"));var rows=new ArrayList<Map<String,Object>>();for(String statement:sql.split(";")){if(!statement.isBlank()){rows.addAll(jdbc.queryForList(statement));}}return rows;}

    static class SchemaObservation {Map<String,String> before,after;int executed,repeated;}
    @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
    static class FailureObservation implements Interceptor {
        final ThreadLocal<String> point=new ThreadLocal<>();
        final ThreadLocal<Runnable> beforeThrow=new ThreadLocal<>();
        final ThreadLocal<Runnable> afterShare=new ThreadLocal<>(),beforeLock=new ThreadLocal<>();
        final ThreadLocal<Runnable> afterGuardLock=new ThreadLocal<>(),beforeGuardLock=new ThreadLocal<>();
        final ThreadLocal<Integer> deadlocksRemaining=new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();
            if(id.endsWith("ProductMapper.lock") && beforeLock.get()!=null){beforeLock.get().run();}
            if((id.endsWith("QualificationGuardMapper.ensureExists") || id.endsWith("QualificationGuardMapper.lockVersion"))
                    && beforeGuardLock.get()!=null){beforeGuardLock.get().run();}
            var result=invocation.proceed();
            if(id.endsWith("ProductMapper.share") && afterShare.get()!=null){afterShare.get().run();}
            if(id.endsWith("QualificationGuardMapper.lockVersion") && afterGuardLock.get()!=null){afterGuardLock.get().run();}
            Integer remaining=deadlocksRemaining.get();
            if(id.endsWith("SkuMapper.decreaseStock") && result instanceof Number affected && affected.intValue()==1 && remaining!=null && remaining>0){
                deadlocksRemaining.set(remaining-1);
                throw new UncategorizedSQLException("Injected product payment deadlock","after stock update",new SQLException("Injected InnoDB deadlock","40001",1213));
            }
            if(point.get()!=null && (id.endsWith("."+point.get()) || (!point.get().contains(".") && id.endsWith("ProductMapper."+point.get())))){
                point.remove();assertThat(result).isInstanceOf(Number.class);assertThat(((Number)result).intValue()).isPositive();
                Runnable assertion=beforeThrow.get();beforeThrow.remove();if(assertion!=null){assertion.run();}
                throw new UncategorizedSQLException("Product test fault","after successful SQL",new SQLException("Injected"));
            }return result;
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class DatabaseConfiguration {
        @Bean SchemaObservation productSchemaObservation(){return new SchemaObservation();}
        @Bean FailureObservation productFailureObservation(){return new FailureObservation();}
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties,SchemaObservation observation) throws Exception {
            var source=new IsolatedQualificationMySqlDataSource(properties,6,List.of());
            try {
                var directory=Files.createDirectories(Path.of("target","product-migrations",UUID.randomUUID().toString()));
                for(String name:List.of("V1__create_schema.sql","V3__create_whitelist_tables.sql","V4__create_free_reservation_tables.sql","V5__create_ticket_refund_tables.sql","V6__add_refund_processing_results.sql","V7__add_gift_inventory_operations.sql","V8__create_product_catalog.sql","V9__create_product_orders.sql","V10__add_qualification_guard_and_order_version.sql","V11__freeze_product_after_sales.sql","V12__separate_after_sale_inspection_and_resalability.sql","V13__create_cache_invalidation_tasks.sql")){
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
