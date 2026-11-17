package com.limitactivitysystem.messaging.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.support.RequiresEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Run separately against retained Day 21 demo data, after workers and expiry scans finish. */
@SpringBootTest(properties={JwtTestTokens.SECRET_PROPERTY,"spring.flyway.enabled=false",
        "spring.task.scheduling.enabled=false","cache.invalidation.scan-delay-ms=3600000"})
@RequiresEnvironmentVariable(named="RUN_DAY21_RECONCILIATION_TESTS",matches="true")
class Day21ReconciliationMySqlIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.limitactivitysystem.cache.CacheInvalidationDelivery invalidationDelivery;

    @Test void allSqlScriptsUseOneReadOnlySnapshotAndSaveEveryResultSet() throws Exception {
        var results=new LinkedHashMap<String,Object>();
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("startedAt",Instant.now().toString());
        metadata.put("status","ERROR");
        metadata.put("completedScripts",0);
        results.put("_meta",metadata);
        var violations=new ArrayList<String>();
        try(Connection connection=dataSource.getConnection()) {
            String database=connection.getCatalog();
            metadata.put("database",database);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM product_order")) {
                    rows.next();
                    long orderCount=rows.getLong(1);
                    metadata.put("productOrderCount",orderCount);
                    if(orderCount==0) {
                        metadata.put("status","PRECONDITION_FAILED");
                        metadata.put("reason","No retained product orders. Point SPRING_DATASOURCE_URL at the prepared demo database; "
                                +"ProductCatalogMySqlIntegrationTest cleans up its isolated fixtures. See docs/day21-regression.md.");
                    }
                    assertThat(orderCount).as("Database '%s' has no retained product orders. "
                            +"Check SPRING_DATASOURCE_URL in THIS IDEA configuration and prepare committed demo orders via the business APIs. "
                            +"ProductCatalogMySqlIntegrationTest deletes its isolated fixtures; an empty database is not M3 evidence. "
                            +"See docs/day21-regression.md and target/day21/reconciliation.json",database).isPositive();
                }
                try(var paths=Files.list(Path.of("tests","sql"))) {
                    var scripts=paths.filter(p->p.toString().endsWith(".sql")).sorted().toList();
                    metadata.put("totalScripts",scripts.size());
                    assertThat(scripts).as("Reconciliation scripts in tests/sql; use the repository root as working directory").isNotEmpty();
                    for(Path script:scripts) {
                        metadata.put("currentScript",script.getFileName().toString());
                        String sql=Files.readString(script).lines().filter(line->!line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
                        var sets=new ArrayList<Object>();
                        results.put(script.getFileName().toString(),sets);
                        for(String part:sql.split(";")) {
                            String query=part.trim();
                            if(query.isEmpty() || query.matches("(?is)^(START TRANSACTION.*|SET TRANSACTION.*|COMMIT)$")) continue;
                            assertThat(query).as("Read-only SQL in %s",script).matches("(?is)^(SELECT|WITH)\\s.*");
                            try(var statement=connection.createStatement();var rows=statement.executeQuery(query)) {
                                var columns=rows.getMetaData();var records=new ArrayList<Map<String,Object>>();boolean invariant=false;
                                for(int i=1;i<=columns.getColumnCount();i++) if("violation".equalsIgnoreCase(columns.getColumnLabel(i))) invariant=true;
                                while(rows.next()) {
                                    var row=new LinkedHashMap<String,Object>();
                                    for(int i=1;i<=columns.getColumnCount();i++) row.put(columns.getColumnLabel(i),rows.getObject(i)==null?null:rows.getString(i));
                                    records.add(row);
                                }
                                sets.add(Map.of("query",query,"rows",records,"invariant",invariant));
                                if(invariant && !records.isEmpty()) violations.add(script.getFileName()+": "+records);
                            }
                        }
                        metadata.put("completedScripts",(int)metadata.get("completedScripts")+1);
                    }
                }
            } finally {connection.rollback();}
            metadata.remove("currentScript");
            metadata.put("status","INVARIANT_VIOLATIONS");
            assertThat(violations).as("Every normal-business invariant must have zero violations").isEmpty();
            metadata.put("status","PASSED");
        } catch(Exception | AssertionError failure) {
            if("PASSED".equals(metadata.get("status"))) metadata.put("status","ERROR");
            metadata.put("failureType",failure.getClass().getName());
            throw failure;
        } finally {
            metadata.put("finishedAt",Instant.now().toString());
            metadata.put("violationResultSetCount",violations.size());
            Path output=Files.createDirectories(Path.of("target","day21")).resolve("reconciliation.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),results);
        }
    }
}
