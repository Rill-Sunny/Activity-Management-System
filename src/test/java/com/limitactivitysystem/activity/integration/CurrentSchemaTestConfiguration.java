package com.limitactivitysystem.activity.integration;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Behavioral regression uses the current schema; historical migration tests keep explicit version targets. */
@TestConfiguration(proxyBeanMethods=false)
public class CurrentSchemaTestConfiguration {
    @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
        var source=new IsolatedQualificationMySqlDataSource(properties,6,java.util.List.of());
        try {
            var directory=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target","current-test-migrations",java.util.UUID.randomUUID().toString()));
            var resolver=new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            for(var resource:resolver.getResources("classpath*:db/migration/V*__*.sql")) {
                String name=resource.getFilename();
                if(name==null || name.startsWith("V2__")) continue;
                try(var input=resource.getInputStream()) {java.nio.file.Files.copy(input,directory.resolve(name));}
            }
            org.flywaydb.core.Flyway.configure().dataSource(source)
                    .locations("filesystem:"+directory.toAbsolutePath().toString().replace('\\','/')).load().migrate();
            return source;
        } catch(Exception failure) {source.close();throw failure;}
    }
}
