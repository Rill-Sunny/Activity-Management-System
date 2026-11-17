import java.nio.file.*;
import java.sql.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Owns only the randomly named suite database. Existing schemas are connection anchors only. */
public class Day22TestDatabase {
    public static void main(String[] args) throws Exception {
        String name=System.getenv("DAY22_SUITE_SCHEMA");
        if(name==null || !name.matches("day22_suite_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid owned schema");
        String base=System.getenv("DAY22_BASE_URL");
        String user=System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME","root");
        String password=System.getenv("DB_PASSWORD");
        Class.forName("com.mysql.cj.jdbc.Driver");
        try(var connection=DriverManager.getConnection(base,user,password);var statement=connection.createStatement()) {
            if(args[0].equals("create")) statement.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4");
            else if(args[0].equals("drop")) {
                statement.execute("DROP DATABASE IF EXISTS `"+name+"`");
                clearOwnedRedis(); return;
            }
            else throw new IllegalArgumentException("Only create/drop supported");
        }
        try(var connection=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),user,password)) {
            var migrations=Files.list(Path.of("src/main/resources/db/migration"));
            try(migrations) {
                for(var file:migrations.filter(p->p.getFileName().toString().matches("V[0-9]+__.*\\.sql"))
                        .filter(p->!p.getFileName().toString().startsWith("V2__"))
                        .sorted(java.util.Comparator.comparingInt(p->Integer.parseInt(p.getFileName().toString().split("__")[0].substring(1))))
                        .toList()) ScriptUtils.executeSqlScript(connection,new FileSystemResource(file));
            }
            var ddl=new StringBuilder("-- Generated from V1, V3 through V17 in an isolated test database. No seed data.\n\n");
            try(var statement=connection.createStatement();var tables=statement.executeQuery("SHOW TABLES")) {
                var names=new java.util.ArrayList<String>(); while(tables.next()) names.add(tables.getString(1));
                for(String table:names) {
                    if(!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected table name");
                    try(var query=connection.createStatement();var definition=query.executeQuery("SHOW CREATE TABLE `"+table+"`")) {
                        definition.next(); ddl.append(definition.getString(2)).append(";\n\n");
                    }
                }
                Files.writeString(Path.of(System.getenv("DAY22_EVIDENCE_DIRECTORY"),"schema.sql"),ddl);
                System.out.println("Application tables (including cache/messaging): "+names.size());
                try(var check=connection.createStatement();var keys=check.executeQuery("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()")) {
                    keys.next(); if(keys.getInt(1)!=0) throw new IllegalStateException("Unexpected physical foreign keys");
                    System.out.println("Physical foreign keys: 0");
                }
            }
        }
        System.out.println("Prepared owned suite database: "+name);
    }
    private static void clearOwnedRedis() {
        String prefix=System.getenv("DAY22_REDIS_PREFIX");
        if(prefix==null || !prefix.matches("las:test:day22:[0-9]{8}-[0-9]{6}-[a-f0-9]{8}:")) {
            throw new IllegalArgumentException("Invalid owned Redis prefix");
        }
        var factory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                System.getenv().getOrDefault("REDIS_HOST","localhost"),Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","6379")));
        factory.afterPropertiesSet(); factory.start();
        try(var connection=factory.getConnection();var keys=connection.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions().match(prefix+"*").count(100).build())) {
            while(keys.hasNext()) connection.keyCommands().del(keys.next());
        } finally { factory.destroy(); }
    }
}
