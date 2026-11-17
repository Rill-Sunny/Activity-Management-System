package com.limitactivitysystem.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Offline contract generation/coverage check; no application context or infrastructure is started. */
class ReleaseApiContractTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ObjectNode schemas = JSON.createObjectNode();

    @Test void checkedInApiMatchesControllersAndDtoConstraints() throws Exception {
        ObjectNode spec = JSON.createObjectNode();
        spec.put("openapi", "3.0.3");
        spec.set("info", JSON.valueToTree(Map.of("title", "Limited Activities System", "version", "1.0.0",
                "description", "模拟支付与退款。LocalDateTime 使用 Asia/Shanghai 本地时间；改期 OffsetDateTime 必须带时区。业务规则及动态 Map 字段见 docs/api。")));
        spec.set("servers", JSON.valueToTree(List.of(Map.of("url", "http://127.0.0.1:8081"), Map.of("url", "http://127.0.0.1:8082"))));
        spec.set("security", JSON.valueToTree(List.of(Map.of("bearerAuth", List.of()))));
        ObjectNode paths = spec.putObject("paths");
        var scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var types = scan.findCandidateComponents("com.limitactivitysystem").stream()
                .map(bean -> bean.getBeanClassName()).sorted().toList();
        int operations = 0;
        for (String name : types) {
            Class<?> type = Class.forName(name);
            if (!type.getProtectionDomain().getCodeSource().getLocation().equals(
                    com.limitactivitysystem.LimitActivitySystemApplication.class.getProtectionDomain().getCodeSource().getLocation())) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            for (Method method : Arrays.stream(type.getDeclaredMethods()).sorted(Comparator.comparing(Method::getName)).toList()) {
                RequestMapping route = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (route == null) continue;
                for (String prefix : values(base)) for (String suffix : values(route)) for (RequestMethod verb : route.method()) {
                    String path = prefix + suffix;
                    ObjectNode pathItem = paths.has(path) ? (ObjectNode)paths.get(path) : paths.putObject(path);
                    ObjectNode operation = pathItem.putObject(verb.name().toLowerCase(Locale.ROOT));
                    String id = type.getSimpleName() + "_" + method.getName();
                    operation.put("operationId", id);
                    operation.put("summary", method.getName());
                    operation.put("x-java-method", name + "#" + method.getName());
                    operation.set("tags", JSON.valueToTree(List.of(type.getPackageName().split("\\.")[2])));
                    boolean publicRoute = path.startsWith("/auth/") || path.equals("/actuator/health") ||
                            verb == RequestMethod.GET && (path.equals("/activities") || path.matches("/activities/\\{[^}]+}") ||
                            path.matches("/products/\\{[^}]+}(/skus/\\{[^}]+})?") || path.matches("/activities/\\{[^}]+}/products") ||
                            path.endsWith("/seat-layout") || path.endsWith("/cancellation"));
                    if (publicRoute) operation.putArray("security");
                    operation.put("x-required-role", publicRoute ? "PUBLIC" : path.startsWith("/admin/") ? "ADMIN" : path.startsWith("/publisher/") ? "PUBLISHER" : "AUTHENTICATED_ACTIVE_USER");
                    ArrayNode parameters = operation.putArray("parameters");
                    for (Parameter parameter : method.getParameters()) {
                        PathVariable pv = parameter.getAnnotation(PathVariable.class);
                        RequestParam query = parameter.getAnnotation(RequestParam.class);
                        RequestBody body = parameter.getAnnotation(RequestBody.class);
                        if (pv != null || query != null) {
                            String field = pv != null ? (pv.value().isEmpty() ? pv.name() : pv.value()) : (query.value().isEmpty() ? query.name() : query.value());
                            if (field.isEmpty()) field = parameter.getName();
                            ObjectNode item = parameters.addObject().put("name", field).put("in", pv != null ? "path" : "query");
                            item.put("required", pv != null || query.required() && query.defaultValue().equals(ValueConstants.DEFAULT_NONE));
                            ObjectNode value = schema(parameter.getParameterizedType());
                            constraints(value, parameter.getAnnotations());
                            if (query != null && !query.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                                value.set("default", JSON.readTree(query.defaultValue().matches("-?[0-9]+|true|false") ? query.defaultValue() : JSON.writeValueAsString(query.defaultValue())));
                            }
                            item.set("schema", value);
                        } else if (body != null && parameter.getType() != String.class) {
                            ObjectNode value = schema(parameter.getParameterizedType());
                            if (Map.class.isAssignableFrom(parameter.getType())) value = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
                            operation.putObject("requestBody").put("required", body.required()).putObject("content")
                                    .putObject("application/json").set("schema", value);
                        }
                    }
                    if (parameters.isEmpty()) operation.remove("parameters");
                    ObjectNode responses = operation.putObject("responses");
                    ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);
                    List<Integer> codes = status == null ? List.of(200) : List.of(status.code().value());
                    if (Set.of("TicketOrderController_create", "ProductOrderController_confirm", "PublisherGiftAllocationController_allocate").contains(id)) codes = List.of(200,201);
                    if (Set.of("TicketRefundController_submit", "ReservationController_create").contains(id)) codes = List.of(201);
                    Type responseType = method.getGenericReturnType();
                    if (responseType instanceof ParameterizedType p && p.getRawType() == ResponseEntity.class) responseType = p.getActualTypeArguments()[0];
                    for (int code : codes) {
                        ObjectNode response = responses.putObject(String.valueOf(code)).put("description", code == 201 ? "Created" : code == 202 ? "Accepted; background task may still be pending" : "Success");
                        if (code != 204 && responseType != void.class && responseType != Void.class) response.putObject("content").putObject("application/json").set("schema", schema(responseType));
                    }
                    responses.putObject("default").put("description", "请求格式、认证、归属、状态/库存冲突或依赖错误。错误码见对应模块文档；超载不保证成功。")
                            .putObject("content").putObject("application/problem+json").set("schema", JSON.createObjectNode().put("type", "object").put("additionalProperties", true));
                    if (!publicRoute) for (String code : List.of("401", "403")) {
                        ObjectNode content = responses.putObject(code).put("description", code.equals("401") ? "Missing/invalid token or inactive user" : "Role or resource access denied").putObject("content");
                        content.putObject("application/json").set("schema", schema(com.limitactivitysystem.security.SecurityErrorResponse.class));
                        content.putObject("application/problem+json").set("schema", JSON.createObjectNode().put("type", "object").put("additionalProperties", true));
                    }
                    operations++;
                }
            }
        }
        ObjectNode components = spec.putObject("components");
        components.set("schemas", schemas);
        components.putObject("securitySchemes").putObject("bearerAuth").put("type", "http").put("scheme", "bearer").put("bearerFormat", "JWT");
        Path document = Path.of("docs/api/openapi.yaml");
        if (Boolean.getBoolean("release.api.export")) {
            Files.createDirectories(document.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(document.toFile(), spec);
        }
        assertThat(JSON.readTree(document.toFile()).equals(JSON.readTree(JSON.writeValueAsBytes(spec))))
                .as("Regenerate with -Drelease.api.export=true after reviewing API changes").isTrue();
        assertThat(operations).isGreaterThan(60);
        if (Boolean.getBoolean("release.classpath")) {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/release-test-classpath.txt"), System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        }
    }

    private static String[] values(RequestMapping mapping) {
        if (mapping == null) return new String[]{""};
        return mapping.path().length > 0 ? mapping.path() : mapping.value().length > 0 ? mapping.value() : new String[]{""};
    }
    private ObjectNode schema(Type type) {
        JavaType value = JSON.constructType(type);
        Class<?> raw = value.getRawClass();
        ObjectNode node = JSON.createObjectNode();
        if (raw == Object.class) return node;
        if (value.isCollectionLikeType() || raw.isArray()) { node.put("type", "array"); node.set("items", schema(value.getContentType())); return node; }
        if (value.isMapLikeType()) { node.put("type", "object"); node.set("additionalProperties", schema(value.getContentType())); return node; }
        if (raw == boolean.class || raw == Boolean.class) return node.put("type", "boolean");
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) return node.put("type", "integer").put("format", raw == long.class || raw == Long.class ? "int64" : "int32");
        if (Number.class.isAssignableFrom(raw)) return node.put("type", "number");
        if (raw == String.class || raw.isEnum()) { node.put("type", "string"); if (raw.isEnum()) node.set("enum", JSON.valueToTree(raw.getEnumConstants())); return node; }
        if (raw.getPackageName().equals("java.time")) {
            node.put("type", "string");
            if (raw == java.time.LocalDateTime.class) node.put("pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?$").put("description", "Asia/Shanghai local date-time without offset");
            else node.put("format", raw == java.time.LocalDate.class ? "date" : "date-time");
            return node;
        }
        String name = raw.getName().replace("com.limitactivitysystem.", "").replace('.', '_').replace('$','_');
        if (!schemas.has(name)) {
            ObjectNode definition = schemas.putObject(name).put("type", "object");
            ObjectNode properties = definition.putObject("properties");
            ArrayNode required = definition.putArray("required");
            var description = JSON.getSerializationConfig().introspect(value);
            for (var property : description.findProperties()) {
                var member = property.getPrimaryMember();
                if (member == null) continue;
                ObjectNode field = schema(member.getType());
                try {
                    Field javaField = raw.getDeclaredField(property.getInternalName());
                    constraints(field, javaField.getAnnotations());
                    if (javaField.getType().isPrimitive() || Arrays.stream(javaField.getAnnotations()).anyMatch(a -> Set.of(NotNull.class,NotBlank.class,NotEmpty.class).contains(a.annotationType()))) required.add(property.getName());
                } catch (NoSuchFieldException ignored) { }
                properties.set(property.getName(), field);
            }
            if (required.isEmpty()) definition.remove("required");
            if (Arrays.stream(raw.getMethods()).anyMatch(m -> m.isAnnotationPresent(JsonAnySetter.class))) definition.put("additionalProperties", false);
        }
        return node.put("$ref", "#/components/schemas/"+name);
    }
    private static void constraints(ObjectNode node, Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Size size) { String unit = node.path("type").asText().equals("array") ? "Items" : "Length"; node.put("min"+unit,size.min()); if (size.max() != Integer.MAX_VALUE) node.put("max"+unit,size.max()); }
            if (annotation instanceof Pattern pattern) node.put("pattern", "^(?:"+pattern.regexp()+")$");
            if (annotation instanceof Positive) node.put("minimum",0).put("exclusiveMinimum",true);
            if (annotation instanceof PositiveOrZero) node.put("minimum",0);
            if (annotation instanceof Min min) node.put("minimum",min.value());
            if (annotation instanceof Max max) node.put("maximum",max.value());
            if (annotation instanceof DecimalMin min) node.put("minimum",new java.math.BigDecimal(min.value())).put("exclusiveMinimum",!min.inclusive());
            if (annotation instanceof NotBlank) node.put("minLength",Math.max(1,node.path("minLength").asInt()));
            if (annotation instanceof NotEmpty) { String key=node.path("type").asText().equals("array")?"minItems":"minLength"; node.put(key,Math.max(1,node.path(key).asInt())); }
        }
    }
}
