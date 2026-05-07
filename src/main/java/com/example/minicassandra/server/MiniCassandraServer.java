package com.example.minicassandra.server;

import com.example.minicassandra.cluster.MiniCassandraCluster;
import com.example.minicassandra.cluster.QuorumException;
import com.example.minicassandra.config.DatabaseConfig;
import com.example.minicassandra.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

public final class MiniCassandraServer {
    private final DatabaseConfig config;
    private final MiniCassandraCluster cluster;
    private final HttpServer server;

    public MiniCassandraServer(DatabaseConfig config, Path dataDir) throws IOException {
        this.config = config;
        this.cluster = new MiniCassandraCluster(config, dataDir);
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.server.createContext("/health", safe(this::health));
        this.server.createContext("/kv", safe(this::kv));
        this.server.createContext("/row", safe(this::row));
        this.server.createContext("/admin", safe(this::admin));
        this.server.createContext("/cluster", safe(this::cluster));
        this.server.setExecutor(Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors() * 2)));
    }

    public void start() {
        server.start();
        System.out.println("Mini Cassandra Java started on http://localhost:" + config.port());
        System.out.println("Data dir: " + config.dataDir());
    }

    private void health(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("nodes", config.nodeCount());
        body.put("replicationFactor", config.replicationFactor());
        body.put("cluster", cluster.status());
        json(exchange, 200, body);
    }

    private void kv(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String table = required(query, "table");
        String key = required(query, "key");
        String column = required(query, "column");
        String method = exchange.getRequestMethod();

        switch (method) {
            case "POST" -> {
                String value = readBody(exchange.getRequestBody());
                cluster.put(table, key, column, value);
                json(exchange, 200, Map.of(
                        "status", "OK",
                        "operation", "PUT",
                        "table", table,
                        "key", key,
                        "column", column,
                        "replicas", cluster.replicasFor(key)
                ));
            }
            case "GET" -> {
                Optional<String> value = cluster.get(table, key, column);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("found", value.isPresent());
                response.put("table", table);
                response.put("key", key);
                response.put("column", column);
                value.ifPresent(v -> response.put("value", v));
                response.put("replicas", cluster.replicasFor(key));
                json(exchange, value.isPresent() ? 200 : 404, response);
            }
            case "DELETE" -> {
                cluster.delete(table, key, column);
                json(exchange, 200, Map.of(
                        "status", "OK",
                        "operation", "DELETE",
                        "table", table,
                        "key", key,
                        "column", column,
                        "replicas", cluster.replicasFor(key)
                ));
            }
            default -> throw new HttpError(405, "Method not allowed: " + method);
        }
    }

    private void row(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String table = required(query, "table");
        String key = required(query, "key");
        Map<String, String> columns = cluster.getRow(table, key);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", !columns.isEmpty());
        response.put("table", table);
        response.put("key", key);
        response.put("columns", columns);
        response.put("replicas", cluster.replicasFor(key));
        json(exchange, columns.isEmpty() ? 404 : 200, response);
    }

    private void admin(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        String path = exchange.getRequestURI().getPath();
        if ("/admin/flush".equals(path)) {
            cluster.flushAll();
            json(exchange, 200, Map.of("status", "OK", "operation", "FLUSH"));
        } else if ("/admin/compact".equals(path)) {
            cluster.compactAll();
            json(exchange, 200, Map.of("status", "OK", "operation", "COMPACT"));
        } else {
            throw new HttpError(404, "Unknown admin endpoint: " + path);
        }
    }

    private void cluster(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/cluster/ring".equals(path)) {
            requireMethod(exchange, "GET");
            json(exchange, 200, cluster.ringStatus());
            return;
        }
        if ("/cluster/status".equals(path)) {
            requireMethod(exchange, "GET");
            json(exchange, 200, cluster.status());
            return;
        }
        String[] parts = path.split("/");
        if (parts.length == 5 && "cluster".equals(parts[1]) && "node".equals(parts[2])) {
            requireMethod(exchange, "POST");
            String nodeId = parts[3];
            String action = parts[4];
            if ("down".equals(action)) {
                cluster.markNodeDown(nodeId);
            } else if ("up".equals(action)) {
                cluster.markNodeUp(nodeId);
            } else {
                throw new HttpError(404, "Unknown node action: " + action);
            }
            json(exchange, 200, Map.of("status", "OK", "nodeId", nodeId, "action", action));
            return;
        }
        throw new HttpError(404, "Unknown cluster endpoint: " + path);
    }

    private HttpHandler safe(ExchangeConsumer consumer) {
        return exchange -> {
            try {
                consumer.accept(exchange);
            } catch (HttpError e) {
                json(exchange, e.statusCode, Map.of("error", e.getMessage()));
            } catch (QuorumException e) {
                json(exchange, 503, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                json(exchange, 500, Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                exchange.close();
            }
        };
    }

    private static void json(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = JsonUtil.object(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equals(exchange.getRequestMethod())) {
            throw new HttpError(405, "Expected method " + expected + " but got " + exchange.getRequestMethod());
        }
    }

    private static String required(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) throw new HttpError(400, "Missing required query parameter: " + name);
        return value;
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return result;
        for (String pair : query.split("&")) {
            if (pair.isBlank()) continue;
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length == 2 ? decode(parts[1]) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String input) {
        return URLDecoder.decode(input, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ExchangeConsumer {
        void accept(HttpExchange exchange) throws Exception;
    }

    private static final class HttpError extends RuntimeException {
        private final int statusCode;

        private HttpError(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
