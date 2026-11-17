import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.LimitActivitySystemApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Data preparation, not a JUnit test. Retains its own schema and uses real HTTP/MQ business flows. */
public final class PrepareDay21Demo {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Map<String, Object>> calls = new ArrayList<>();
    private final String suffix = schemaSuffix();
    private final String schema = "day21_demo_" + suffix;
    private final String rabbitPrefix = "day21.demo." + suffix + ".";
    private final Path output = Path.of("docs", "verification", "day21", schema);
    private HttpClient http;
    private JdbcTemplate jdbc;
    private String base;
    private String publisher;
    private String buyer;

    public static void main(String[] args) throws Exception {
        new PrepareDay21Demo().prepare();
    }

    private void prepare() throws Exception {
        Files.createDirectories(output);
        String originalUrl = env("DAY21_DEMO_BASE_URL", "jdbc:mysql://127.0.0.1:3306/limit_activity_system?serverTimezone=Asia/Shanghai");
        String username = env("DAY21_DEMO_DB_USER", "root");
        String password = System.getenv("DB_PASSWORD");
        require(password != null && !password.isBlank(), "DB_PASSWORD is required");
        require(System.getenv("RABBITMQ_PASSWORD") != null, "RABBITMQ_PASSWORD is required");
        int query = originalUrl.indexOf('?');
        String withoutQuery = query < 0 ? originalUrl : originalUrl.substring(0, query);
        int slash = withoutQuery.lastIndexOf('/');
        require(originalUrl.startsWith("jdbc:mysql://") && slash >= "jdbc:mysql://".length(), "MySQL URL must include a database");
        String url = withoutQuery.substring(0, slash + 1) + schema + (query < 0 ? "" : originalUrl.substring(query));
        boolean resume = System.getenv("DAY21_DEMO_RESUME_SCHEMA") != null;
        if (resume) {
            Path previous = output.resolve("preparation.json");
            var saved = json.readValue(previous.toFile(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            require(schema.equals(saved.get("schema")) && url.equals(saved.get("jdbcUrl"))
                    && "FAILED".equals(saved.get("status")) && saved.containsKey("businessIds"),
                    "Resume requires an owned failed report whose business journey already completed");
            Files.copy(previous, output.resolve("preparation.previous-" + System.currentTimeMillis() + ".json"));
            evidence.putAll(saved);
            evidence.put("resumedAt", Instant.now().toString());
        }
        evidence.put("schema", schema);
        evidence.put("jdbcUrl", url);
        evidence.put("databaseUser", username);
        evidence.put("rabbitPrefix", rabbitPrefix);
        evidence.putIfAbsent("startedAt", Instant.now().toString());
        evidence.put("status", "PREPARING");
        evidence.putIfAbsent("httpSteps", calls);
        System.out.println("Retained demo database: " + schema);
        try {
            // Never connect the application to the original database and never drop an existing schema.
            if (!resume) {
                try (var admin = DriverManager.getConnection(originalUrl, username, password);
                     var statement = admin.createStatement()) {
                    statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
                }
            }
            Path migrations = Files.createDirectories(output.resolve("migrations"));
            try (var paths = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".sql")).toList()) {
                    if (!resume && !path.getFileName().toString().startsWith("V2__")) Files.copy(path, migrations.resolve(path.getFileName()));
                }
            }
            try (var context = new SpringApplicationBuilder(LimitActivitySystemApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0",
                    "--spring.datasource.url=" + url, "--spring.datasource.username=" + username,
                    "--spring.flyway.enabled=true", "--spring.flyway.locations=filesystem:" + migrations.toAbsolutePath().toString().replace('\\', '/'),
                    "--spring.rabbitmq.dynamic=true", "--spring.rabbitmq.listener.simple.auto-startup=true",
                    "--messaging.rabbit.name-prefix=" + rabbitPrefix, "--cache.redis.key-prefix=" + rabbitPrefix,
                    "--ticket-order.expiration.enabled=false", "--product-order.expiration.enabled=false",
                    "--outbox.relay.initial-delay-ms=500", "--outbox.relay.scan-delay-ms=500",
                    "--qualification.after-sale.retry-initial-delay-ms=500", "--qualification.after-sale.retry-delay-ms=500",
                    "--management.tracing.enabled=false")) {
                jdbc = context.getBean(JdbcTemplate.class);
                base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                    http = client;
                    if (!resume) journey();
                    captureState();
                    var admin = context.getBean(org.springframework.amqp.rabbit.core.RabbitAdmin.class);
                    var queues = new ArrayList<Object>();
                    for (var field : com.limitactivitysystem.messaging.RabbitMqTopology.class.getFields()) {
                        if (!field.getName().endsWith("_QUEUE")) continue;
                        String name = rabbitPrefix + field.get(null);
                        var info = admin.getQueueInfo(name);
                        require(info != null, "Missing owned Rabbit queue: " + name);
                        queues.add(Map.of("name", name, "readyMessages", info.getMessageCount(), "consumers", info.getConsumerCount()));
                    }
                    evidence.put("rabbitQueues", queues);
                }
            }
            // Workers are stopped before taking the independent read-only reconciliation snapshot.
            reconcile(url, username, password);
            evidence.remove("failureType");
            evidence.put("status", "PREPARED");
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAILED");
            evidence.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            evidence.put("finishedAt", Instant.now().toString());
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("preparation.json").toFile(), evidence);
        }
        System.out.println("PREPARED: " + output.toAbsolutePath());
        System.out.println("JUnit/Maven tests were not executed. Run Day21-Reconciliation against this retained database.");
    }

    private void journey() throws Exception {
        String accountPassword = UUID.randomUUID().toString();
        String publisherName = "d21-publisher-" + suffix.substring(0, 8);
        String buyerName = "d21-buyer-" + suffix.substring(0, 8);
        request("/auth/register", null, Map.of("username", publisherName, "password", accountPassword), 201);
        request("/auth/register", null, Map.of("username", buyerName, "password", accountPassword), 201);
        // The only seed update is a role grant for the new publisher in this newly owned database.
        require(jdbc.update("UPDATE app_user SET role='PUBLISHER' WHERE username=? AND role='USER'", publisherName) == 1, "Publisher seed role");
        publisher = request("/auth/login", null, Map.of("username", publisherName, "password", accountPassword), 200).path("accessToken").asText();
        buyer = request("/auth/login", null, Map.of("username", buyerName, "password", accountPassword), 200).path("accessToken").asText();
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("accounts.local.json").toFile(),
                Map.of("publisher", publisherName, "buyer", buyerName, "password", accountPassword));
        long buyerId = number("SELECT id FROM app_user WHERE username=?", buyerName);
        var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        long activity = request("/publisher/activities", publisher, Map.of("name", "Day21 retained demo", "type", "PAID",
                "openTime", now.minusDays(10).toString(), "closeTime", now.plusDays(1).toString()), 201).path("activityId").asLong();
        long[] first = session(activity, now.plusDays(20));
        long[] second = session(activity, now.plusDays(21));
        request("/publisher/activities/" + activity + "/publish", publisher, null, 204);
        JsonNode firstTickets = ticketOrder(first[0], first[1], 2);
        JsonNode otherTickets = ticketOrder(second[0], second[1], 1);

        long shirt = product(activity, "Shared limit T-shirt", "PUBLIC", null, null, 2);
        long black = sku(shirt, "BLACK-M", 10);
        long white = sku(shirt, "WHITE-L", 10);
        publish(shirt);
        long sale = productOrder(List.of(item(black, 1), item(white, 1)));
        require(number("SELECT valid_count FROM user_product_purchase WHERE user_id=? AND product_id=?", buyerId, shirt) == 2, "Shared Product limit after two SKUs");
        var denied = request("/product-orders/confirm", buyer,
                Map.of("requestId", key(), "items", List.of(item(black, 1))), 409);
        require("PRODUCT_PURCHASE_LIMIT_EXCEEDED".equals(denied.path("code").asText()), "Shared limit must reject a third item");
        long blackItem = orderItem(sale, black);
        request("/publisher/product-orders/" + sale + "/ship", publisher, Map.of("orderItemId", blackItem, "quantity", 1), 200);
        require(number("SELECT remains_stock FROM sku WHERE sku_id=?", black) == 9, "Shipping must not restore inventory");
        request("/product-orders/" + sale + "/receive", buyer, Map.of("orderItemId", blackItem, "quantity", 1), 200);
        var afterSale = request("/product-after-sales", buyer, Map.of("requestId", key(), "orderId", sale, "items",
                List.of(Map.of("orderItemId", blackItem, "quantity", 1, "reason", "NO_REASON",
                        "simulateInspectionPassed", true, "simulateResalable", true))), 201);
        require("SUCCEEDED".equals(afterSale.path("status").asText()), "Partial after-sale must succeed");
        require(number("SELECT remains_stock FROM sku WHERE sku_id=?", black) == 10, "Resalable return must restore only A");

        long gift = product(activity, "Session one gift", "TICKET_REQUIRED", "SESSION", first[0], 2);
        long giftSku = sku(gift, "GIFT", 10); publish(gift);
        long other = product(activity, "Session two gift", "TICKET_REQUIRED", "SESSION", second[0], 2);
        long otherSku = sku(other, "OTHER-GIFT", 10); publish(other);
        long shipped = product(activity, "Session one shipped gift", "TICKET_REQUIRED", "SESSION", first[0], 2);
        long shippedSku = sku(shipped, "SHIPPED-GIFT", 10); publish(shipped);
        long mixed = productOrder(List.of(item(white, 1), item(giftSku, 1), item(otherSku, 1)));
        long shippedOrder = productOrder(List.of(item(shippedSku, 1)));
        long shippedItem = orderItem(shippedOrder, shippedSku);
        request("/publisher/product-orders/" + shippedOrder + "/ship", publisher, Map.of("orderItemId", shippedItem, "quantity", 1), 200);

        long firstRefund = refund(firstTickets.path("orderId").asLong(), firstTickets.path("tickets").get(0).path("ticketId").asLong());
        await("first eligibility event consumed", () -> number("SELECT COUNT(*) FROM consumer_process cp JOIN outbox_event oe ON oe.event_id=cp.event_id WHERE oe.event_type='TICKET_QUALIFICATION_CHANGED' AND oe.aggregate_id=? AND cp.consumer_name='ticket-qualification-after-sale-v1'", firstRefund) == 1);
        require(number("SELECT COUNT(*) FROM ticket_qualification_after_sale_task") == 0, "One remaining valid ticket must retain eligibility");
        long lastRefund = refund(firstTickets.path("orderId").asLong(), firstTickets.path("tickets").get(1).path("ticketId").asLong());
        long giftItem = orderItem(mixed, giftSku);
        await("unshipped qualification refund", () -> number("SELECT COUNT(*) FROM ticket_qualification_after_sale_task WHERE product_order_item_id=? AND status='SUCCEEDED'", giftItem) == 1);
        await("shipped qualification return task", () -> number("SELECT COUNT(*) FROM ticket_qualification_after_sale_task WHERE product_order_item_id=? AND status='WAITING_RETURN'", shippedItem) == 1);
        require(number("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?", orderItem(mixed, white)) == 0, "Ordinary item must be retained");
        require(number("SELECT refunded_quantity FROM product_order_item WHERE order_item_id=?", orderItem(mixed, otherSku)) == 0, "Other session eligibility must be retained");
        require(number("SELECT remains_stock FROM sku WHERE sku_id=?", giftSku) == 10, "Unshipped gift refund restock");
        require(number("SELECT remains_stock FROM sku WHERE sku_id=?", shippedSku) == 9, "WAITING_RETURN must not restock");
        // ORDER_EXPIRED is a scheduled wake-up published at order creation, with the real 15-minute TTL.
        // Do not pretend these future messages are already consumed or shorten the business deadline.
        await("published events and immediate consumers", () -> number("SELECT COUNT(*) FROM outbox_event oe WHERE oe.status<>'SENT' OR (oe.event_type<>'ORDER_EXPIRED' AND NOT EXISTS (SELECT 1 FROM consumer_process cp WHERE cp.event_id=oe.event_id))") == 0);
        require(number("SELECT COUNT(*) FROM consumer_failure") == 0, "No consumer failures expected in demo preparation");
        evidence.put("businessIds", Map.of("activityId", activity, "sessionOne", first[0], "sessionTwo", second[0],
                "partialAfterSaleOrder", sale, "mixedOrder", mixed, "waitingReturnOrder", shippedOrder,
                "firstTicketRefund", firstRefund, "lastTicketRefund", lastRefund, "retainedTicketOrder", otherTickets.path("orderId").asLong()));
        System.out.println("Business data prepared through HTTP and real RabbitMQ consumers.");
    }

    private void captureState() {
        evidence.put("productOrders", jdbc.queryForList("SELECT order_id,status,total_amount FROM product_order ORDER BY order_id"));
        evidence.put("stock", jdbc.queryForList("SELECT sku_id,product_id,sku_name,total_stock,remains_stock FROM sku ORDER BY sku_id"));
        evidence.put("tasks", jdbc.queryForList("SELECT task_id,source_event_id,product_order_item_id,status,attempt_count FROM ticket_qualification_after_sale_task ORDER BY task_id"));
        evidence.put("events", jdbc.queryForList("SELECT oe.event_id,oe.event_type,oe.status,(SELECT COUNT(*) FROM consumer_process cp WHERE cp.event_id=oe.event_id) AS consumer_count FROM outbox_event oe ORDER BY oe.created_at,oe.event_id"));
        evidence.put("scheduledExpiryMessages", jdbc.queryForList("SELECT oe.event_id,o.order_id,o.status AS order_status,o.expires_at FROM outbox_event oe JOIN ticket_order o ON o.order_id=oe.aggregate_id WHERE oe.event_type='ORDER_EXPIRED' AND NOT EXISTS (SELECT 1 FROM consumer_process cp WHERE cp.event_id=oe.event_id)"));
        evidence.put("scheduledExpiryNote", "Future expiry wake-ups remain in the owned durable delay queue. Resume the application with this schema and rabbitPrefix to consume them when due; they must not change paid/refunded orders.");
        evidence.put("waitingReturnNextStep", "Retain shipped goods awaiting physical return and inspection; no stock was restored.");
    }

    private long[] session(long activity, LocalDateTime start) throws Exception {
        long session = request("/publisher/activities/" + activity + "/sessions", publisher, Map.of("startTime", start.toString(),
                "endTime", start.plusHours(2).toString(), "capacity", 4, "maxTicketsPerUser", 4, "seatMode", "AUTO_ASSIGN"), 201).path("activitySessionId").asLong();
        require(session > 0, "Session response ID");
        long zone = request("/publisher/sessions/" + session + "/zones", publisher,
                Map.of("name", "PAID", "zoneType", "PAID", "price", 100, "totalAmount", 4), 201).path("zoneId").asLong();
        var seats = new ArrayList<Map<String, Integer>>();
        for (int i = 1; i <= 4; i++) seats.add(Map.of("rowNo", 1, "seatNo", i, "allocationPriority", i));
        request("/publisher/zones/" + zone + "/seats", publisher, Map.of("seats", seats), 201);
        return new long[]{session, zone};
    }

    private JsonNode ticketOrder(long session, long zone, int quantity) throws Exception {
        long order = request("/ticket-orders", buyer, Map.of("requestId", key(), "sessionId", session, "items",
                List.of(Map.of("zoneId", zone, "allocationMode", "AUTO_ASSIGN", "quantity", quantity, "acceptNonAdjacent", false))), 201).path("orderId").asLong();
        return request("/ticket-orders/" + order + "/pay", buyer, null, 200);
    }

    private long product(long activity, String name, String eligibility, String scope, Long session, int limit) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("activityId", activity); body.put("name", name); body.put("qualificationType", eligibility);
        body.put("purchaseLimit", limit); body.put("refundPolicy", "STANDARD_7D");
        if (scope != null) body.put("qualificationScope", scope);
        if (session != null) body.put("qualificationSessionId", session);
        return request("/publisher/products", publisher, body, 201).path("productId").asLong();
    }

    private long sku(long product, String name, int stock) throws Exception {
        return request("/publisher/products/" + product + "/skus", publisher,
                Map.of("name", name, "price", 99, "stock", stock), 201).path("skuId").asLong();
    }
    private void publish(long product) throws Exception { request("/publisher/products/" + product + "/publish", publisher, null, 200); }
    private Map<String, Object> item(long sku, int quantity) { return Map.of("skuId", sku, "quantity", quantity); }
    private long productOrder(List<Map<String, Object>> items) throws Exception {
        require(request("/product-orders/preview", buyer, Map.of("items", items), 200).path("available").asBoolean(), "Product preview");
        long order = request("/product-orders/confirm", buyer, Map.of("requestId", key(), "items", items), 201).path("orderId").asLong();
        require("PAID".equals(request("/product-orders/" + order + "/pay", buyer, null, 200).path("status").asText()), "Product payment");
        return order;
    }
    private long refund(long order, long ticket) throws Exception {
        long refund = request("/ticket-refunds", buyer, Map.of("requestId", key(), "orderId", order,
                "ticketIds", List.of(ticket), "reason", "USER_REQUEST"), 201).path("refundId").asLong();
        require("SUCCEEDED".equals(request("/ticket-refunds/" + refund + "/process", buyer, null, 200).path("status").asText()), "Ticket refund");
        return refund;
    }
    private long orderItem(long order, long sku) { return number("SELECT order_item_id FROM product_order_item WHERE order_id=? AND sku_id=?", order, sku); }
    private long number(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private String key() { return "day21-" + UUID.randomUUID(); }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static String schemaSuffix() {
        String resume = System.getenv("DAY21_DEMO_RESUME_SCHEMA");
        if (resume == null) return UUID.randomUUID().toString().replace("-", "");
        require(resume.matches("day21_demo_[a-f0-9]{32}"), "Invalid owned demo schema name");
        return resume.substring("day21_demo_".length());
    }
    private static void require(boolean satisfied, String description) {
        if (!satisfied) throw new IllegalStateException(description);
    }
    private void await(String description, BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Timed out: " + description);
            Thread.sleep(100);
        }
    }
    private JsonNode request(String path, String token, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        var response = http.send(builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        calls.add(Map.of("path", path, "httpStatus", response.statusCode()));
        JsonNode parsed = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        require(response.statusCode() == expected, "HTTP " + path + " expected=" + expected + " actual=" + response.statusCode() + " code=" + parsed.path("code").asText());
        return parsed;
    }

    private void reconcile(String url, String username, String password) throws Exception {
        var results = new LinkedHashMap<String, Object>();
        int violations = 0;
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var files = Files.list(Path.of("tests", "sql"))) {
                for (var file : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                    var sets = new ArrayList<Object>();
                    results.put(file.getFileName().toString(), sets);
                    String script = Files.readString(file).lines().filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
                    for (String part : script.split(";")) {
                        String query = part.trim();
                        if (query.isEmpty() || query.matches("(?is)^(START TRANSACTION.*|SET TRANSACTION.*|COMMIT)$")) continue;
                        require(query.matches("(?is)^(SELECT|WITH)\\s.*"), "Read-only reconciliation script: " + file);
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                            var columns = rows.getMetaData();
                            boolean invariant = false;
                            var records = new ArrayList<Object>();
                            for (int i = 1; i <= columns.getColumnCount(); i++) invariant |= "violation".equalsIgnoreCase(columns.getColumnLabel(i));
                            while (rows.next()) {
                                var record = new LinkedHashMap<String, Object>();
                                for (int i = 1; i <= columns.getColumnCount(); i++) record.put(columns.getColumnLabel(i), rows.getString(i));
                                records.add(record);
                            }
                            if (invariant) violations += records.size();
                            sets.add(Map.of("query", query, "invariant", invariant, "rows", records));
                        }
                    }
                }
            } finally { connection.rollback(); }
        } finally {
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("preparation-reconciliation.json").toFile(), results);
        }
        evidence.put("reconciliationScripts", results.size());
        evidence.put("invariantViolations", violations);
        require(results.size() > 0 && violations == 0, "Prepared data must have zero invariant violations");
    }
}
