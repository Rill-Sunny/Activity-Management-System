package com.limitactivitysystem.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Explicitly opted-in faults against existing, disposable Compose services. Never pulls images. */
public final class ComposeServiceFault implements AutoCloseable {
    private final List<String> command;
    private final String service;
    private boolean stopped;

    public ComposeServiceFault(String service) throws Exception {
        if (!"true".equals(System.getenv("RUN_DAY21_FAULT_TESTS"))) {
            throw new IllegalStateException("RUN_DAY21_FAULT_TESTS=true is required in the IDEA environment variables."
                    + " Leave this scenario disabled when an isolated Docker Compose environment is not configured."
                    + " Enable this test only against the existing isolated Docker Compose services described in docs/day21-regression.md.");
        }
        if (!List.of("redis", "rabbitmq").contains(service)) throw new IllegalArgumentException(service);
        this.service = service;
        Path compose = Path.of(required("DAY21_COMPOSE_FILE")).toAbsolutePath();
        Path env = Path.of(required("DAY21_COMPOSE_ENV_FILE")).toAbsolutePath();
        if (!Files.isRegularFile(compose) || !Files.isRegularFile(env)) {
            throw new IllegalStateException("Compose and env files must already exist");
        }
        command = List.of("docker", "compose", "--project-name", required("DAY21_COMPOSE_PROJECT"),
                "--env-file", env.toString(), "-f", compose.toString());
        String id = run("ps", "--status", "running", "-q", service).trim();
        if (id.isEmpty()) throw new IllegalStateException("Expected an existing running test container: " + service);
    }

    public void stop() throws Exception {
        stopped = true; // Even a partial/failed stop must attempt recovery in close().
        run("stop", "--timeout", "10", service);
    }

    public void start() throws Exception {
        run("start", service);
        stopped = false;
    }

    @Override public void close() throws Exception { if (stopped) start(); }

    public static void await(String description, Duration timeout, BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (ready.getAsBoolean()) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out: " + description);
    }

    private String run(String... arguments) throws Exception {
        var args = new ArrayList<>(command);
        args.addAll(List.of(arguments));
        Path output = Files.createDirectories(Path.of("target", "day21", "compose"))
                .resolve(service + "-" + System.nanoTime() + ".log");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Compose timed out; inspect " + output);
            }
            if (process.exitValue() != 0) throw new AssertionError("Compose failed; inspect " + output);
            return Files.readString(output);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
