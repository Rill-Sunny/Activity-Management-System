import java.nio.file.*;
import java.sql.*;
import org.flywaydb.core.Flyway;

/** Prepares only the owned Docker test schema. Demo DML is excluded; all business DDL uses Flyway. */
public class Day26TestDatabase {
    public static void main(String[] args) throws Exception {
        String schema = System.getenv("DAY26_SUITE_SCHEMA");
        if (schema == null || !schema.matches("day26_suite_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid owned schema");
        String base = System.getenv("DAY26_BASE_URL");
        if (base == null || !base.startsWith("jdbc:mysql://127.0.0.1:")) throw new IllegalArgumentException("Isolated loopback MySQL required");
        String password = System.getenv("DB_PASSWORD");
        try (var connection = DriverManager.getConnection(base, "root", password); var statement = connection.createStatement()) {
            try (var version = statement.executeQuery("SELECT VERSION()")) {
                version.next();
                if (!version.getString(1).startsWith("8.4.11")) throw new IllegalStateException("MySQL 8.4.11 required");
            }
            if (args[0].equals("drop")) { statement.execute("DROP DATABASE IF EXISTS `" + schema + "`"); return; }
            if (!args[0].equals("create")) throw new IllegalArgumentException("Only create/drop supported");
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        Path migrations = Files.createDirectories(Path.of(System.getenv("DAY26_PHASE_DIRECTORY"), "runner", "migrations"));
        try (var paths = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path source : paths.filter(p -> p.getFileName().toString().matches("V[0-9]+__.*\\.sql"))
                    .filter(p -> !p.getFileName().toString().startsWith("V2__")).toList()) {
                Files.copy(source, migrations.resolve(source.getFileName()));
            }
        }
        var flyway = Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"), "root", password)
                .locations("filesystem:" + migrations.toAbsolutePath().toString().replace('\\','/'))
                .cleanDisabled(true).load();
        flyway.migrate(); flyway.validate();
        System.out.println("Owned schema migrated and validated: " + schema);
    }
}
