package ru.tcb.sal.commands.probe;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 *   <li>{@code PROBE_BINDINGS} — обязательно, формат
 *       {@code exchange1:routingKey1,exchange2:routingKey2,...}</li>
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

            for (Binding b : cfg.bindings) {
                channel.queueBind(queueName, b.exchange, b.routingKey);
                System.out.println("  bound to exchange='" + b.exchange + "' key='" + b.routingKey + "'");
            }

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

    private static final class Config {
        String host;
        int port;
        String vhost;
        String username;
        String password;
        List<Binding> bindings;
        String outputDir;
        int maxMessages;

        static Config fromEnv() {
            Config c = new Config();
            c.host = require("RMQ_HOST");
            c.port = Integer.parseInt(env("RMQ_PORT", "5672"));
            c.vhost = env("RMQ_VHOST", "/");
            c.username = require("RMQ_USER");
            c.password = require("RMQ_PASS");
            c.outputDir = env("PROBE_OUTPUT_DIR", "./probe-output");
            c.maxMessages = Integer.parseInt(env("PROBE_MAX_MESSAGES", "0"));

            String bindingsRaw = require("PROBE_BINDINGS");
            c.bindings = new ArrayList<>();
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
            if (c.bindings.isEmpty()) {
                throw new IllegalArgumentException("PROBE_BINDINGS is empty");
            }
            return c;
        }

        void print() {
            System.out.println("==== sal-wire-probe ====");
            System.out.println("RMQ:    " + host + ":" + port + " vhost=" + vhost);
            System.out.println("User:   " + username);
            System.out.println("Output: " + outputDir);
            System.out.println("Max:    " + (maxMessages > 0 ? maxMessages : "unlimited"));
            System.out.println("Bindings:");
            for (Binding b : bindings) {
                System.out.println("  - " + b.exchange + " :: " + b.routingKey);
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
