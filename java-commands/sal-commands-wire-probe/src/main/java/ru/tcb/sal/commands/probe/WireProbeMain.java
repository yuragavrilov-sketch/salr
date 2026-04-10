package ru.tcb.sal.commands.probe;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.LongString;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone RMQ message capture utility.
 *
 * <p>Создаёт временную auto-delete очередь, биндит её к указанным
 * exchange'ам и сохраняет каждое прилетевшее сообщение как
 * pretty-printed JSON-дамп: envelope, все AMQP properties, все headers
 * (значения {@code LongString} нормализуются в обычные строки), body
 * как Base64 + UTF-8 строка.
 *
 * <p>Не часть publishable артефактов — dev-tool.
 *
 * <p>Конфигурация через переменные окружения:
 * <ul>
 *   <li>{@code RMQ_HOST} — обязательно</li>
 *   <li>{@code RMQ_PORT} — по умолчанию 5672</li>
 *   <li>{@code RMQ_VHOST} — по умолчанию /</li>
 *   <li>{@code RMQ_USER} — обязательно</li>
 *   <li>{@code RMQ_PASS} — обязательно</li>
 *   <li>{@code PROBE_BINDINGS} — формат
 *       {@code exchange1:routingKey1,exchange2:routingKey2,...} (опционально)</li>
 *   <li>{@code PROBE_CLONE_QUEUES} — список очередей через запятую,
 *       binding'и которых надо клонировать в нашу temp queue (опционально).
 *       Удобно для зеркалирования всего трафика существующего .NET-адаптера —
 *       тулз получит копии всех его команд и результатов всех типов сразу.</li>
 *   <li>{@code PROBE_CLONE_ALL} — если {@code true}, тулз обходит ВСЕ очереди
 *       vhost'а через Management API, собирает их bindings (с дедупликацией)
 *       и применяет к нашей temp queue. Это закрывает кейс «поймать вообще всё,
 *       что течёт через шину» без необходимости знать имена очередей и
 *       routing keys заранее. Системные очереди ({@code amq.*}) и другие
 *       probe-очереди (с префиксом {@code sal-wire-probe-}) пропускаются.</li>
 *   <li>Должно быть задано минимум одно из {@code PROBE_BINDINGS} /
 *       {@code PROBE_CLONE_QUEUES} / {@code PROBE_CLONE_ALL}.</li>
 *   <li>{@code RMQ_MGMT_PORT} — порт RMQ Management HTTP API, по умолчанию 15672
 *       (нужен только при использовании {@code PROBE_CLONE_QUEUES})</li>
 *   <li>{@code RMQ_MGMT_SCHEME} — http или https, по умолчанию http</li>
 *   <li>{@code PROBE_OUTPUT_DIR} — по умолчанию ./probe-output</li>
 *   <li>{@code PROBE_MAX_MESSAGES} — по умолчанию 0 (без ограничения, до Ctrl+C)</li>
 * </ul>
 *
 * <p>Пример:
 * <pre>
 * RMQ_HOST=test-rmq.example RMQ_USER=guest RMQ_PASS=guest \
 *   PROBE_BINDINGS=TCB.Infrastructure.Command.CommandCompletedEvent:CommandTester_xxx \
 *   PROBE_MAX_MESSAGES=5 \
 *   mvn -pl sal-commands-wire-probe -am compile exec:java
 * </pre>
 */
public final class WireProbeMain {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        Config cfg = Config.fromEnv();
        cfg.print();

        Path outDir = Paths.get(cfg.outputDir).toAbsolutePath();
        Files.createDirectories(outDir);
        System.out.println("Output dir: " + outDir);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(cfg.host);
        factory.setPort(cfg.port);
        factory.setVirtualHost(cfg.vhost);
        factory.setUsername(cfg.username);
        factory.setPassword(cfg.password);
        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);

        try (Connection connection = factory.newConnection("sal-wire-probe");
             Channel channel = connection.createChannel()) {

            String queueName = "sal-wire-probe-" + UUID.randomUUID().toString().substring(0, 8);
            // durable=false, exclusive=true, autoDelete=true — очередь умрёт вместе с probe
            channel.queueDeclare(queueName, false, true, true, null);
            System.out.println("Declared temp queue: " + queueName);

            // Aggregate explicit + cloned bindings, deduplicated
            java.util.LinkedHashSet<Binding> allBindings = new java.util.LinkedHashSet<>(cfg.bindings);

            List<String> queuesToClone = new ArrayList<>(cfg.cloneQueues);
            if (cfg.cloneAll) {
                System.out.println("Discovering all queues in vhost via Management API...");
                List<String> discovered = listAllQueues(cfg);
                int kept = 0;
                for (String q : discovered) {
                    if (q.startsWith("amq.")) continue;             // skip system queues
                    if (q.startsWith("sal-wire-probe-")) continue;  // skip our own / sibling probes
                    queuesToClone.add(q);
                    kept++;
                }
                System.out.println("  found " + discovered.size() + " queues, "
                    + kept + " eligible for cloning");
            }

            for (String sourceQueue : queuesToClone) {
                List<Binding> cloned;
                try {
                    cloned = fetchQueueBindings(cfg, sourceQueue);
                } catch (Exception ex) {
                    System.err.println("Failed to fetch bindings for '" + sourceQueue + "': " + ex.getMessage());
                    continue;
                }
                int newOnes = 0;
                for (Binding b : cloned) {
                    if (allBindings.add(b)) newOnes++;
                }
                System.out.println("  cloned from '" + sourceQueue + "': "
                    + cloned.size() + " bindings (" + newOnes + " new)");
            }

            if (allBindings.isEmpty()) {
                throw new IllegalStateException(
                    "No bindings to apply. Set PROBE_BINDINGS / PROBE_CLONE_QUEUES / PROBE_CLONE_ALL.");
            }

            System.out.println("Applying " + allBindings.size() + " unique binding(s) to temp queue...");
            int applied = 0;
            for (Binding b : allBindings) {
                try {
                    channel.queueBind(queueName, b.exchange, b.routingKey);
                    applied++;
                } catch (Exception ex) {
                    System.err.println("  binding failed: " + b.exchange + " :: " + b.routingKey
                        + " — " + ex.getMessage());
                }
            }
            System.out.println("  " + applied + " binding(s) applied.");

            AtomicInteger counter = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);

            channel.basicConsume(queueName, false, "wire-probe", new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) throws IOException {
                    int n = counter.incrementAndGet();
                    String filename = String.format("msg-%03d-%s.json", n, sanitize(envelope.getRoutingKey()));
                    Path target = outDir.resolve(filename);

                    try {
                        Map<String, Object> dump = buildDump(envelope, properties, body);
                        MAPPER.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), dump);
                        System.out.printf("[%d] %s -> %s (%d bytes)%n",
                            n, envelope.getRoutingKey(), filename, body.length);
                    } catch (Exception e) {
                        System.err.println("Failed to dump message #" + n + ": " + e.getMessage());
                        e.printStackTrace();
                    }

                    channel.basicAck(envelope.getDeliveryTag(), false);

                    if (cfg.maxMessages > 0 && n >= cfg.maxMessages) {
                        done.countDown();
                    }
                }
            });

            System.out.println();
            System.out.println("Probe is running. Waiting for messages on temp queue.");
            System.out.println(cfg.maxMessages > 0
                ? "Will stop after " + cfg.maxMessages + " messages."
                : "Press Ctrl+C to stop.");
            System.out.println();

            if (cfg.maxMessages > 0) {
                done.await();
                System.out.println("Captured " + counter.get() + " messages. Exiting.");
            } else {
                Thread.currentThread().join();
            }
        }
    }

    private static Map<String, Object> buildDump(Envelope envelope,
                                                  AMQP.BasicProperties props,
                                                  byte[] body) {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("capturedAt", Instant.now().toString());

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("exchange", envelope.getExchange());
        env.put("routingKey", envelope.getRoutingKey());
        env.put("redelivered", envelope.isRedeliver());
        env.put("deliveryTag", envelope.getDeliveryTag());
        dump.put("envelope", env);

        Map<String, Object> p = new LinkedHashMap<>();
        if (props != null) {
            p.put("contentType", props.getContentType());
            p.put("contentEncoding", props.getContentEncoding());
            p.put("deliveryMode", props.getDeliveryMode());
            p.put("priority", props.getPriority());
            p.put("correlationId", props.getCorrelationId());
            p.put("replyTo", props.getReplyTo());
            p.put("expiration", props.getExpiration());
            p.put("messageId", props.getMessageId());
            p.put("type", props.getType());
            p.put("userId", props.getUserId());
            p.put("appId", props.getAppId());
            p.put("clusterId", props.getClusterId());
            Date ts = props.getTimestamp();
            p.put("timestamp", ts != null ? ts.toInstant().toString() : null);
            p.put("timestampEpochSeconds", ts != null ? ts.getTime() / 1000L : null);

            Map<String, Object> hdrs = new LinkedHashMap<>();
            if (props.getHeaders() != null) {
                for (Map.Entry<String, Object> e : props.getHeaders().entrySet()) {
                    hdrs.put(e.getKey(), normalizeHeader(e.getValue()));
                }
            }
            p.put("headers", hdrs);
        }
        dump.put("properties", p);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("length", body.length);
        b.put("base64", Base64.getEncoder().encodeToString(body));
        b.put("utf8", new String(body, StandardCharsets.UTF_8));
        b.put("looksLikeJson", looksLikeJson(body));
        dump.put("body", b);

        return dump;
    }

    /**
     * Нормализует значения AMQP-headers в plain Java-типы:
     * {@link LongString} → {@link String}, вложенные {@code Map}/{@code List} рекурсивно.
     */
    private static Object normalizeHeader(Object value) {
        if (value == null) return null;
        if (value instanceof LongString ls) return ls.toString();
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(e.getKey().toString(), normalizeHeader(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(WireProbeMain::normalizeHeader).toList();
        }
        return value;
    }

    private static boolean looksLikeJson(byte[] body) {
        if (body.length == 0) return false;
        byte first = body[0];
        return first == '{' || first == '[' || first == '"';
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "no-key";
        String sanitized = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.length() > 60 ? sanitized.substring(0, 60) : sanitized;
    }

    /**
     * Возвращает имена всех очередей в vhost через Management API.
     */
    private static List<String> listAllQueues(Config cfg) throws Exception {
        String encodedVhost = URLEncoder.encode(cfg.vhost, StandardCharsets.UTF_8);
        String url = String.format("%s://%s:%d/api/queues/%s",
            cfg.mgmtScheme, cfg.host, cfg.mgmtPort, encodedVhost);
        JsonNode arr = mgmtGetArray(cfg, url);
        List<String> result = new ArrayList<>();
        for (JsonNode q : arr) {
            String name = q.path("name").asText("");
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }

    /**
     * Читает bindings очереди через RMQ Management HTTP API.
     * Возвращает только {@code queue}-destination bindings и пропускает
     * default exchange (source = "").
     */
    private static List<Binding> fetchQueueBindings(Config cfg, String queueName) throws Exception {
        String encodedVhost = URLEncoder.encode(cfg.vhost, StandardCharsets.UTF_8);
        String encodedQueue = URLEncoder.encode(queueName, StandardCharsets.UTF_8);
        String url = String.format("%s://%s:%d/api/queues/%s/%s/bindings",
            cfg.mgmtScheme, cfg.host, cfg.mgmtPort, encodedVhost, encodedQueue);

        JsonNode arr = mgmtGetArray(cfg, url);
        List<Binding> result = new ArrayList<>();
        for (JsonNode b : arr) {
            String source = b.path("source").asText("");
            String key = b.path("routing_key").asText("");
            String destType = b.path("destination_type").asText("");
            if (source.isEmpty()) continue;            // skip default exchange binding
            if (!"queue".equals(destType)) continue;   // skip exchange-to-exchange
            result.add(new Binding(source, key));
        }
        return result;
    }

    private static JsonNode mgmtGetArray(Config cfg, String url) throws Exception {
        String basicAuth = Base64.getEncoder().encodeToString(
            (cfg.username + ":" + cfg.password).getBytes(StandardCharsets.UTF_8));

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Basic " + basicAuth)
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Mgmt API GET " + url + " returned HTTP "
                + response.statusCode() + ": " + response.body());
        }
        JsonNode arr = MAPPER.readTree(response.body());
        if (!arr.isArray()) {
            throw new IOException("Mgmt API response is not an array: " + response.body());
        }
        return arr;
    }

    private static final class Config {
        String host;
        int port;
        String vhost;
        String username;
        String password;
        List<Binding> bindings;
        List<String> cloneQueues;
        boolean cloneAll;
        String mgmtScheme;
        int mgmtPort;
        String outputDir;
        int maxMessages;

        static Config fromEnv() {
            Config c = new Config();
            c.host = require("RMQ_HOST");
            c.port = Integer.parseInt(env("RMQ_PORT", "5672"));
            c.vhost = env("RMQ_VHOST", "/");
            c.username = require("RMQ_USER");
            c.password = require("RMQ_PASS");
            c.mgmtScheme = env("RMQ_MGMT_SCHEME", "http");
            c.mgmtPort = Integer.parseInt(env("RMQ_MGMT_PORT", "15672"));
            c.outputDir = env("PROBE_OUTPUT_DIR", "./probe-output");
            c.maxMessages = Integer.parseInt(env("PROBE_MAX_MESSAGES", "0"));

            c.bindings = new ArrayList<>();
            String bindingsRaw = env("PROBE_BINDINGS", "");
            if (!bindingsRaw.isEmpty()) {
                for (String pair : bindingsRaw.split(",")) {
                    String trimmed = pair.trim();
                    if (trimmed.isEmpty()) continue;
                    int colon = trimmed.indexOf(':');
                    if (colon < 0) {
                        throw new IllegalArgumentException(
                            "Invalid PROBE_BINDINGS entry (expected 'exchange:routingKey'): " + trimmed);
                    }
                    String exchange = trimmed.substring(0, colon).trim();
                    String routingKey = trimmed.substring(colon + 1).trim();
                    c.bindings.add(new Binding(exchange, routingKey));
                }
            }

            c.cloneQueues = new ArrayList<>();
            String cloneRaw = env("PROBE_CLONE_QUEUES", "");
            if (!cloneRaw.isEmpty()) {
                for (String q : cloneRaw.split(",")) {
                    String trimmed = q.trim();
                    if (!trimmed.isEmpty()) c.cloneQueues.add(trimmed);
                }
            }

            c.cloneAll = Boolean.parseBoolean(env("PROBE_CLONE_ALL", "false"));

            if (c.bindings.isEmpty() && c.cloneQueues.isEmpty() && !c.cloneAll) {
                throw new IllegalArgumentException(
                    "Set at least one of PROBE_BINDINGS / PROBE_CLONE_QUEUES / PROBE_CLONE_ALL");
            }
            return c;
        }

        void print() {
            System.out.println("==== sal-wire-probe ====");
            System.out.println("RMQ AMQP:  " + host + ":" + port + " vhost=" + vhost);
            System.out.println("RMQ Mgmt:  " + mgmtScheme + "://" + host + ":" + mgmtPort);
            System.out.println("User:      " + username);
            System.out.println("Output:    " + outputDir);
            System.out.println("Max msgs:  " + (maxMessages > 0 ? maxMessages : "unlimited"));
            if (!bindings.isEmpty()) {
                System.out.println("Explicit bindings:");
                for (Binding b : bindings) {
                    System.out.println("  - " + b.exchange + " :: " + b.routingKey);
                }
            }
            if (!cloneQueues.isEmpty()) {
                System.out.println("Clone queues:");
                for (String q : cloneQueues) {
                    System.out.println("  - " + q);
                }
            }
            if (cloneAll) {
                System.out.println("Clone ALL queues in vhost: true");
            }
            System.out.println("========================");
        }

        private static String env(String key, String def) {
            String v = System.getenv(key);
            return (v != null && !v.isEmpty()) ? v : def;
        }

        private static String require(String key) {
            String v = System.getenv(key);
            if (v == null || v.isEmpty()) {
                throw new IllegalStateException("Environment variable " + key + " is required");
            }
            return v;
        }
    }

    private record Binding(String exchange, String routingKey) {}

    private WireProbeMain() {}
}
