# Java Command Bus — Starter Core (Plan 2A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Превратить проверенные паттерны из `sal-commands-demo-app` в полноценные модули starter'а: `CommandBus` bean для отправки команд, `@CommandHandler` для приёма, request/reply через `CorrelationStore`, transport-абстракция.

**Architecture:** Извлечение логики из demo-app → sal-commands-core. Transport-абстракция (`CommandTransport`) изолирует AMQP от бизнес-логики. `InMemoryCommandTransport` для тестов без RMQ. `CommandBus` фасад для отправителя, `CommandListener` + `@CommandHandler` BeanPostProcessor для получателя. CorrelationStore + CompletableFuture.orTimeout для request/reply.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring AMQP, Jackson, JUnit 5 + AssertJ.

**Prototype reference:** `sal-commands-demo-app/` содержит рабочий E2E код — все wire-format решения проверены на реальном .NET стенде.

**Base directory:** `/mnt/c/work/sal_refactoring/java-commands/`

---

## Предварительные требования

- Plan 1 завершён, ветка смержена в main
- Demo-app E2E проверен на реальном .NET стенде
- 19 тестов зелёных в sal-commands-core

---

## Файловая карта

### sal-commands-api/ (новые файлы)

| Файл | Что делает |
|---|---|
| `api/CommandBus.java` | Интерфейс фасада: `execute`, `executeAsync`, `publish`, `send` |
| `api/CommandContext.java` | Record с типизированными полями (без .NET-опечаток) |
| `api/CommandInvocation.java` | Fluent builder `withTimeout`, `withPriority`, `withHeader` |
| `api/annotation/CommandHandler.java` | Аннотация для методов-обработчиков |
| `api/annotation/ErrorPolicy.java` | Enum: REPLY_WITH_FAILURE, REJECT_TO_DLQ |

### sal-commands-core/ (новые файлы)

| Файл | Что делает |
|---|---|
| `core/transport/CommandTransport.java` | Интерфейс: `publish(AmqpWireMessage)`, `consume(queue, callback)` |
| `core/transport/amqp/SpringAmqpCommandTransport.java` | Реализация через RabbitTemplate + SimpleMessageListenerContainer |
| `core/transport/amqp/AmqpTopologyConfigurer.java` | Декларирует exchanges, queues, bindings |
| `core/bus/CommandBusImpl.java` | Реализация CommandBus: строит RecordedMessage, публикует, ждёт результат |
| `core/bus/CorrelationStore.java` | `ConcurrentHashMap<correlationId, CompletableFuture>` + Pending record |
| `core/bus/TimeoutWatcher.java` | `@Scheduled` cleanup просроченных futures |
| `core/handler/CommandHandlerRegistry.java` | Реестр `wireName → HandlerBinding` |
| `core/handler/HandlerBinding.java` | Record: wireName, commandClass, MethodHandle, argResolvers |
| `core/handler/CommandHandlerBeanPostProcessor.java` | Сканирует beans, находит @CommandHandler, строит HandlerBinding |
| `core/listener/CommandListener.java` | Принимает команды из CommandExchange, диспатчит в handler, отправляет результат |
| `core/listener/ResultListener.java` | Принимает результаты, резолвит CompletableFuture в CorrelationStore |
| `core/listener/ResponseBuilder.java` | Строит CommandCompletedEvent/CommandFailedEvent (извлечено из demo-app IncomingCommandHandler) |

### sal-commands-core/ (тесты)

| Файл | Что тестирует |
|---|---|
| `test/.../bus/CorrelationStoreTest.java` | Register, remove, timeout, size |
| `test/.../bus/CommandBusImplTest.java` | Execute с mock transport, timeout, publish failure |
| `test/.../handler/CommandHandlerRegistryTest.java` | Register, duplicate detection, find by wireName |
| `test/.../handler/CommandHandlerBeanPostProcessorTest.java` | Scan bean, validate signature, build binding |
| `test/.../listener/ResponseBuilderTest.java` | Build completed/failed events, .NET format assertions |
| `test/.../listener/CommandListenerTest.java` | Receive command, dispatch, send result |
| `test/.../transport/InMemoryCommandTransportTest.java` | Full round-trip без RMQ |

---

## Task 1: api/CommandBus интерфейс + CommandContext record

**Files:**
- Create: `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandBus.java`
- Create: `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandContext.java`
- Test: `sal-commands-api/src/test/java/ru/tcb/sal/commands/api/CommandContextTest.java`

- [ ] **Step 1: Написать `CommandContext` record**

```java
package ru.tcb.sal.commands.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Метаданные выполнения команды — чистый Java record без .NET-опечаток.
 * Маппинг из WireCommandContext (с опечатками) в этот record — внутренний.
 */
public record CommandContext(
    String commandType,
    String correlationId,
    String sourceServiceId,
    Instant timestamp,
    String priority,
    String executionServiceId,
    Instant executionTimestamp,
    Duration executionDuration,
    String sessionId,
    String operationId
) {
    /** Парсит .NET TimeSpan "HH:mm:ss.ttttttt" в Duration. */
    public static Duration parseDotNetTimeSpan(String ts) {
        if (ts == null || ts.isEmpty()) return Duration.ZERO;
        String[] parts = ts.split(":");
        if (parts.length != 3) return Duration.ZERO;
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        String[] secParts = parts[2].split("\\.");
        int seconds = Integer.parseInt(secParts[0]);
        long nanos = 0;
        if (secParts.length > 1) {
            String frac = secParts[1];
            // .NET ticks = 100ns; pad/truncate to 7 digits
            while (frac.length() < 7) frac += "0";
            if (frac.length() > 7) frac = frac.substring(0, 7);
            nanos = Long.parseLong(frac) * 100; // ticks → nanos
        }
        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds).plusNanos(nanos);
    }
}
```

- [ ] **Step 2: Написать тест для parseDotNetTimeSpan**

```java
package ru.tcb.sal.commands.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class CommandContextTest {
    @Test
    void parseDotNetTimeSpan_realValue() {
        Duration d = CommandContext.parseDotNetTimeSpan("00:00:00.2392809");
        assertThat(d.toMillis()).isEqualTo(239);
        assertThat(d.toNanos() % 1_000_000).isEqualTo(280900L);
    }

    @Test
    void parseDotNetTimeSpan_zero() {
        assertThat(CommandContext.parseDotNetTimeSpan("00:00:00.0000000")).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseDotNetTimeSpan_nullOrEmpty() {
        assertThat(CommandContext.parseDotNetTimeSpan(null)).isEqualTo(Duration.ZERO);
        assertThat(CommandContext.parseDotNetTimeSpan("")).isEqualTo(Duration.ZERO);
    }
}
```

- [ ] **Step 3: Написать `CommandBus` интерфейс**

```java
package ru.tcb.sal.commands.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Фасад для отправки команд через шину.
 */
public interface CommandBus {

    /** Синхронный вызов с дефолтным таймаутом. Бросает при ошибке/таймауте. */
    <R extends CommandResult> R execute(CommandWithResult<R> command);

    /** Асинхронный вызов с дефолтным таймаутом. */
    <R extends CommandResult> CompletableFuture<R> executeAsync(CommandWithResult<R> command);

    /** Асинхронный вызов с явным таймаутом и приоритетом. */
    <R extends CommandResult> CompletableFuture<R> executeAsync(
        CommandWithResult<R> command, Duration timeout, CommandPriority priority);

    /** Fire-and-forget. Возвращает correlationId. */
    String publish(Command command);
}
```

- [ ] **Step 4: Запустить тесты, убедиться что зелёные**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-api -am test
```

- [ ] **Step 5: Коммит**

```bash
git add java-commands/sal-commands-api/
git commit -m "feat(sal-commands-api): add CommandBus interface and CommandContext record"
```

---

## Task 2: @CommandHandler аннотация + ErrorPolicy

**Files:**
- Create: `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/annotation/CommandHandler.java`
- Create: `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/annotation/ErrorPolicy.java`
- Test: `sal-commands-api/src/test/java/ru/tcb/sal/commands/api/annotation/CommandHandlerTest.java`

- [ ] **Step 1: Написать `ErrorPolicy` enum**

```java
package ru.tcb.sal.commands.api.annotation;

public enum ErrorPolicy {
    /** Ловим throwable, упаковываем в CommandFailedEvent, отправляем отправителю. */
    REPLY_WITH_FAILURE,
    /** В DLQ, отправитель получит таймаут. */
    REJECT_TO_DLQ
}
```

- [ ] **Step 2: Написать `@CommandHandler` аннотацию**

```java
package ru.tcb.sal.commands.api.annotation;

import java.lang.annotation.*;

/**
 * Помечает метод как обработчик SAL-команды. Фреймворк найдёт метод при
 * старте через BeanPostProcessor, определит тип команды из первого
 * параметра, создаст binding в RMQ и будет маршрутизировать входящие
 * команды этого типа в этот метод.
 *
 * <p>Разрешённые сигнатуры:
 * <pre>
 * {@literal @}CommandHandler
 * public MyResult handle(MyCommand cmd) { ... }
 *
 * {@literal @}CommandHandler
 * public MyResult handle(MyCommand cmd, CommandContext ctx) { ... }
 *
 * {@literal @}CommandHandler
 * public CompletableFuture&lt;MyResult&gt; handleAsync(MyCommand cmd) { ... }
 *
 * {@literal @}CommandHandler
 * public void handleFireAndForget(MyCommand cmd) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommandHandler {

    /** Явный wire-name команды. Если пустой — берётся из @WireName первого параметра. */
    String value() default "";

    /** Политика обработки ошибок. */
    ErrorPolicy onError() default ErrorPolicy.REPLY_WITH_FAILURE;
}
```

- [ ] **Step 3: Написать тест**

```java
package ru.tcb.sal.commands.api.annotation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommandHandlerTest {

    @CommandHandler
    void noArgs() {}

    @CommandHandler(value = "TCB.Test.Cmd", onError = ErrorPolicy.REJECT_TO_DLQ)
    void withArgs() {}

    @Test
    void defaultValues() throws Exception {
        CommandHandler ann = getClass().getDeclaredMethod("noArgs").getAnnotation(CommandHandler.class);
        assertThat(ann.value()).isEmpty();
        assertThat(ann.onError()).isEqualTo(ErrorPolicy.REPLY_WITH_FAILURE);
    }

    @Test
    void explicitValues() throws Exception {
        CommandHandler ann = getClass().getDeclaredMethod("withArgs").getAnnotation(CommandHandler.class);
        assertThat(ann.value()).isEqualTo("TCB.Test.Cmd");
        assertThat(ann.onError()).isEqualTo(ErrorPolicy.REJECT_TO_DLQ);
    }
}
```

- [ ] **Step 4: Запустить тесты**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-api -am test
```

- [ ] **Step 5: Коммит**

```bash
git add java-commands/sal-commands-api/
git commit -m "feat(sal-commands-api): add @CommandHandler annotation and ErrorPolicy"
```

---

## Task 3: CorrelationStore + TimeoutWatcher

**Files:**
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/bus/CorrelationStore.java`
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/bus/TimeoutWatcher.java`
- Test: `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/bus/CorrelationStoreTest.java`

- [ ] **Step 1: Написать тест**

```java
package ru.tcb.sal.commands.core.bus;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class CorrelationStoreTest {

    @Test
    void registerAndRemove_returnsOriginal() {
        CorrelationStore store = new CorrelationStore();
        CompletableFuture<Object> future = new CompletableFuture<>();
        store.register("cid-1", new CorrelationStore.Pending("cid-1",
            Instant.now().plusSeconds(60), future, Object.class));

        assertThat(store.size()).isEqualTo(1);

        CorrelationStore.Pending removed = store.remove("cid-1");
        assertThat(removed).isNotNull();
        assertThat(removed.future()).isSameAs(future);
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void remove_unknown_returnsNull() {
        CorrelationStore store = new CorrelationStore();
        assertThat(store.remove("unknown")).isNull();
    }

    @Test
    void expired_returnsOnlyExpired() {
        CorrelationStore store = new CorrelationStore();
        store.register("expired", new CorrelationStore.Pending("expired",
            Instant.now().minusSeconds(10), new CompletableFuture<>(), Object.class));
        store.register("alive", new CorrelationStore.Pending("alive",
            Instant.now().plusSeconds(60), new CompletableFuture<>(), Object.class));

        var expired = store.expired(Instant.now());
        assertThat(expired).hasSize(1);
        assertThat(expired.iterator().next().correlationId()).isEqualTo("expired");
    }

    @Test
    void doubleRemove_secondReturnsNull() {
        CorrelationStore store = new CorrelationStore();
        store.register("cid", new CorrelationStore.Pending("cid",
            Instant.now().plusSeconds(60), new CompletableFuture<>(), Object.class));
        assertThat(store.remove("cid")).isNotNull();
        assertThat(store.remove("cid")).isNull();
    }
}
```

- [ ] **Step 2: Запустить тест — должен упасть**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core -am test
```

- [ ] **Step 3: Написать `CorrelationStore`**

```java
package ru.tcb.sal.commands.core.bus;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CorrelationStore {

    public record Pending(
        String correlationId,
        Instant expireAt,
        CompletableFuture<?> future,
        Class<?> expectedResultType
    ) {}

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public void register(String cid, Pending p) { pending.put(cid, p); }
    public Pending remove(String cid) { return pending.remove(cid); }

    public Collection<Pending> expired(Instant now) {
        return pending.values().stream()
            .filter(p -> p.expireAt().isBefore(now))
            .toList();
    }

    public int size() { return pending.size(); }
}
```

- [ ] **Step 4: Написать `TimeoutWatcher`**

```java
package ru.tcb.sal.commands.core.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Фоновый процесс: раз в 30 с обходит CorrelationStore и завершает
 * просроченные futures с TimeoutException. Второй уровень защиты после
 * CompletableFuture.orTimeout (на случай если future уже resolved).
 */
public class TimeoutWatcher {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWatcher.class);

    private final CorrelationStore store;
    private final ScheduledExecutorService scheduler;

    public TimeoutWatcher(CorrelationStore store) {
        this.store = store;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sal-timeout-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
        log.info("[TIMEOUT] Watcher started (30s interval)");
    }

    public void stop() {
        scheduler.shutdown();
        log.info("[TIMEOUT] Watcher stopped");
    }

    private void sweep() {
        var expired = store.expired(Instant.now());
        for (var p : expired) {
            if (store.remove(p.correlationId()) != null) {
                p.future().completeExceptionally(
                    new TimeoutException("Command " + p.correlationId() + " timed out"));
                log.warn("[TIMEOUT] Expired: correlationId={}", p.correlationId());
            }
        }
    }
}
```

- [ ] **Step 5: Запустить тесты — должны пройти**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core -am test
```

- [ ] **Step 6: Коммит**

```bash
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add CorrelationStore and TimeoutWatcher"
```

---

## Task 4: ResponseBuilder — сборка CommandCompletedEvent/CommandFailedEvent

**Files:**
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/listener/ResponseBuilder.java`
- Test: `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/listener/ResponseBuilderTest.java`

Извлекает из demo-app `IncomingCommandHandler.buildContext()` + event-сборку в переиспользуемый компонент. Это самая важная часть wire-format — .NET TimeSpan, timestamp без TZ, опечатки Excution*, Priority как строка.

- [ ] **Step 1: Написать тест**

```java
package ru.tcb.sal.commands.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.core.wire.CommandCompletedEvent;
import ru.tcb.sal.commands.core.wire.CommandFailedEvent;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseBuilderTest {

    private final ResponseBuilder builder = new ResponseBuilder(new ObjectMapper(), "TestAdapter");

    @Test
    void buildCompleted_hasCorrectContextFields() {
        RecordedMessage incomingCmd = new RecordedMessage();
        incomingCmd.correlationId = "cid-1";
        incomingCmd.routingKey = "TCB.Test.PingCommand";
        incomingCmd.sourceServiceId = "CallerAdapter";
        incomingCmd.priority = 5;
        incomingCmd.timeStamp = Instant.parse("2026-04-13T10:00:00Z");

        ObjectNode result = new ObjectMapper().createObjectNode();
        result.put("Echo", "hello");

        RecordedMessage response = builder.buildCompleted(
            incomingCmd, result, "TCB.Test.PingResult, TCB.Test",
            Duration.ofMillis(50), "session-base64");

        assertThat(response.exchangeName).isEqualTo("TCB.Infrastructure.Command.CommandCompletedEvent");
        assertThat(response.routingKey).isEqualTo("CallerAdapter");
        assertThat(response.correlationId).isEqualTo("cid-1");
        assertThat(response.contentType).isEqualTo(
            "TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure");
    }

    @Test
    void buildCompleted_contextHasDotNetFormats() throws Exception {
        RecordedMessage incomingCmd = new RecordedMessage();
        incomingCmd.correlationId = "cid-1";
        incomingCmd.routingKey = "TCB.Test.PingCommand";
        incomingCmd.sourceServiceId = "CallerAdapter";
        incomingCmd.priority = 5;
        incomingCmd.timeStamp = Instant.parse("2026-04-13T10:00:00Z");

        RecordedMessage response = builder.buildCompleted(
            incomingCmd, new ObjectMapper().createObjectNode(),
            "TCB.Test.PingResult, TCB.Test",
            Duration.ofMillis(239), null);

        // Parse the payload to check Context fields
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = (ObjectNode) mapper.readTree(mapper.writeValueAsBytes(response.payload));
        ObjectNode ctx = (ObjectNode) body.get("Context");

        assertThat(ctx.get("CommandType").asText()).isEqualTo("TCB.Test.PingCommand");
        assertThat(ctx.get("Priority").asText()).isEqualTo("Normal");
        assertThat(ctx.get("ExcutionServiceId").asText()).isEqualTo("TestAdapter"); // .NET typo
        assertThat(ctx.get("ExcutionDuration").asText()).startsWith("00:00:00.");  // .NET TimeSpan
    }

    @Test
    void buildFailed_hasExceptionData() throws Exception {
        RecordedMessage incomingCmd = new RecordedMessage();
        incomingCmd.correlationId = "cid-1";
        incomingCmd.routingKey = "TCB.Test.PingCommand";
        incomingCmd.sourceServiceId = "CallerAdapter";
        incomingCmd.priority = 5;
        incomingCmd.timeStamp = Instant.parse("2026-04-13T10:00:00Z");

        Exception error = new RuntimeException("Something failed");

        RecordedMessage response = builder.buildFailed(
            incomingCmd, error, Duration.ofMillis(10), null);

        assertThat(response.exchangeName).isEqualTo("TCB.Infrastructure.Command.CommandFailedEvent");
        assertThat(response.routingKey).isEqualTo("CallerAdapter");
        assertThat(response.contentType).isEqualTo(
            "TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = (ObjectNode) mapper.readTree(mapper.writeValueAsBytes(response.payload));
        assertThat(body.has("ExceptionData")).isTrue();
        assertThat(body.get("ExceptionData").get("Message").asText()).isEqualTo("Something failed");
    }
}
```

- [ ] **Step 2: Запустить тест — должен упасть**

- [ ] **Step 3: Написать `ResponseBuilder`**

Извлечь из `sal-commands-demo-app/IncomingCommandHandler.java`: методы `buildContext()`, `sendFailedEvent()`, константы `COMPLETED_EXCHANGE` и т.д. Формат .NET TimeSpan, timestamp без TZ, опечатки `Excution*`, Priority как строка — всё из проверенного demo-app кода.

```java
package ru.tcb.sal.commands.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds wire-format-correct CommandCompletedEvent / CommandFailedEvent
 * response messages. All .NET format nuances are handled here:
 * timestamp formats, TimeSpan, ExcutionServiceId typo, Priority as string.
 */
public class ResponseBuilder {

    public static final String COMPLETED_EXCHANGE = "TCB.Infrastructure.Command.CommandCompletedEvent";
    public static final String COMPLETED_CONTENT_TYPE = "TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure";
    public static final String FAILED_EXCHANGE = "TCB.Infrastructure.Command.CommandFailedEvent";
    public static final String FAILED_CONTENT_TYPE = "TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure";

    private static final DateTimeFormatter DOTNET_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DOTNET_TS_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX");

    private final ObjectMapper mapper;
    private final String adapterName;
    private final AtomicLong messageIdCounter = new AtomicLong(1000);

    public ResponseBuilder(ObjectMapper mapper, String adapterName) {
        this.mapper = mapper;
        this.adapterName = adapterName;
    }

    public RecordedMessage buildCompleted(RecordedMessage incomingCmd, Object result,
                                           String resultTypeAssemblyQualified,
                                           Duration executionDuration, String sessionBase64) {
        ObjectNode eventBody = mapper.createObjectNode();
        eventBody.put("ResultType", resultTypeAssemblyQualified);
        eventBody.set("Result", mapper.valueToTree(result));
        eventBody.set("AdditionalData", mapper.createObjectNode());
        eventBody.set("Context", buildContext(incomingCmd, executionDuration));

        return buildResponseMessage(incomingCmd, COMPLETED_EXCHANGE,
            COMPLETED_CONTENT_TYPE, eventBody, sessionBase64);
    }

    public RecordedMessage buildFailed(RecordedMessage incomingCmd, Throwable error,
                                        Duration executionDuration, String sessionBase64) {
        ObjectNode exData = mapper.createObjectNode();
        exData.put("ExceptionType", error.getClass().getName());
        exData.put("Code", "JavaHandlerException");
        exData.put("Message", error.getMessage());
        exData.put("AdapterName", adapterName);
        exData.put("SourceType", "CommandHandler");
        exData.put("SourcePath", incomingCmd.routingKey);
        exData.put("SessionId", "");
        exData.put("SourceId", incomingCmd.correlationId);
        exData.put("TimeStamp", Instant.now().toString());
        exData.set("Properties", mapper.createObjectNode().put("Type", error.getClass().getName()));
        exData.putNull("InnerException");

        ObjectNode eventBody = mapper.createObjectNode();
        eventBody.set("ExceptionData", exData);
        eventBody.set("AdditionalData", mapper.createObjectNode());
        eventBody.set("Context", buildContext(incomingCmd, executionDuration));

        return buildResponseMessage(incomingCmd, FAILED_EXCHANGE,
            FAILED_CONTENT_TYPE, eventBody, sessionBase64);
    }

    private RecordedMessage buildResponseMessage(RecordedMessage incomingCmd,
                                                  String exchange, String contentType,
                                                  ObjectNode eventBody, String sessionBase64) {
        RecordedMessage rm = new RecordedMessage();
        rm.exchangeName = exchange;
        rm.routingKey = incomingCmd.sourceServiceId;
        rm.correlationId = incomingCmd.correlationId;
        rm.contentType = contentType;
        rm.messageId = String.valueOf(messageIdCounter.incrementAndGet());
        rm.priority = incomingCmd.priority;
        rm.timeStamp = Instant.now();
        rm.payload = eventBody;
        rm.acceptLanguage = "ru-RU";

        rm.additionalData = new LinkedHashMap<>();
        if (sessionBase64 != null) rm.additionalData.put("Session", sessionBase64);
        rm.additionalData.put("SourceServiceId", adapterName);

        return rm;
    }

    private ObjectNode buildContext(RecordedMessage incomingCmd, Duration executionDuration) {
        Instant now = Instant.now();
        ObjectNode ctx = mapper.createObjectNode();
        ctx.put("CommandType", incomingCmd.routingKey);
        ctx.put("CorrelationId", incomingCmd.correlationId);
        ctx.put("SourceServiceId", incomingCmd.sourceServiceId != null
            ? incomingCmd.sourceServiceId : "");

        if (incomingCmd.timeStamp != null) {
            ctx.put("TimeStamp", DOTNET_TS.format(incomingCmd.timeStamp.atOffset(ZoneOffset.ofHours(3))));
        } else {
            ctx.put("TimeStamp", DOTNET_TS.format(now.atOffset(ZoneOffset.ofHours(3))));
        }

        ctx.put("Priority", "Normal");
        ctx.put("ExcutionServiceId", adapterName);
        ctx.put("ExcutionTimeStamp", DOTNET_TS_OFFSET.format(now.atOffset(ZoneOffset.ofHours(3))));
        ctx.put("ExcutionDuration", formatDotNetTimeSpan(executionDuration));
        ctx.put("SessionId", "");
        ctx.put("OperationId", "");
        return ctx;
    }

    static String formatDotNetTimeSpan(Duration duration) {
        long totalNanos = duration.toNanos();
        long hours = totalNanos / 3_600_000_000_000L;
        long minutes = (totalNanos % 3_600_000_000_000L) / 60_000_000_000L;
        long seconds = (totalNanos % 60_000_000_000L) / 1_000_000_000L;
        long ticks = (totalNanos % 1_000_000_000L) / 100L;
        return String.format("%02d:%02d:%02d.%07d", hours, minutes, seconds, ticks);
    }
}
```

- [ ] **Step 4: Запустить тесты**
- [ ] **Step 5: Коммит**

```bash
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add ResponseBuilder for .NET-format events"
```

---

## Task 5: CommandTransport интерфейс + SpringAmqpCommandTransport

**Files:**
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/transport/CommandTransport.java`
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/transport/amqp/SpringAmqpCommandTransport.java`

Извлекает AMQP publish/consume из demo-app в переиспользуемый transport. Интерфейс простой: `publish(AmqpWireMessage) → CompletableFuture<Void>`.

- [ ] **Step 1: Написать `CommandTransport`**

```java
package ru.tcb.sal.commands.core.transport;

import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import java.util.concurrent.CompletableFuture;

/**
 * Абстракция над транспортом команд.
 * Prod: SpringAmqpCommandTransport. Test: InMemoryCommandTransport.
 */
public interface CommandTransport {
    /** Публикует сообщение. Возвращает future, resolved при подтверждении (или при записи в in-memory). */
    CompletableFuture<Void> publish(RecordedMessageConverter.AmqpWireMessage message);
}
```

- [ ] **Step 2: Написать `SpringAmqpCommandTransport`**

```java
package ru.tcb.sal.commands.core.transport.amqp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ru.tcb.sal.commands.core.transport.CommandTransport;

import java.util.concurrent.CompletableFuture;

public class SpringAmqpCommandTransport implements CommandTransport {

    private static final Logger log = LoggerFactory.getLogger(SpringAmqpCommandTransport.class);

    private final RabbitTemplate rabbitTemplate;

    public SpringAmqpCommandTransport(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public CompletableFuture<Void> publish(RecordedMessageConverter.AmqpWireMessage wire) {
        try {
            MessageProperties props = new MessageProperties();
            if (wire.contentType() != null) props.setContentType(wire.contentType());
            if (wire.correlationId() != null) props.setCorrelationId(wire.correlationId());
            if (wire.messageId() != null) props.setMessageId(wire.messageId());
            props.setPriority(wire.priority());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            if (wire.timestamp() != null) props.setTimestamp(wire.timestamp());
            if (wire.expiration() != null) props.setExpiration(wire.expiration());
            if (wire.headers() != null) {
                wire.headers().forEach(props::setHeader);
            }

            Message message = new Message(wire.body(), props);
            rabbitTemplate.send(wire.exchange(), wire.routingKey(), message);

            log.info("[TRANSPORT] Published: exchange='{}' routingKey='{}' correlationId={}",
                wire.exchange(), wire.routingKey(), wire.correlationId());

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.error("[TRANSPORT] Publish failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
```

- [ ] **Step 3: Коммит**

```bash
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add CommandTransport interface + SpringAmqpCommandTransport"
```

---

## Task 6: CommandBusImpl — send path

**Files:**
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/bus/CommandBusImpl.java`
- Test: `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/bus/CommandBusImplTest.java`

Извлекает send-логику из `CommandSenderService`: строит RecordedMessage, публикует, CorrelationStore для request/reply.

- [ ] **Step 1: Написать тест с mock transport**

```java
package ru.tcb.sal.commands.core.bus;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.api.*;
import ru.tcb.sal.commands.api.annotation.WireName;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.CommandTransport;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBusImplTest {

    @WireName(value = "TCB.Test.PingCommand", assembly = "TCB.Test")
    static class PingCommand implements CommandWithResult<PingResult> {
        public String text;
    }

    @WireName(value = "TCB.Test.PingResult", assembly = "TCB.Test")
    static class PingResult implements CommandResult {
        public String echo;
    }

    private CommandBusImpl bus;
    private CorrelationStore store;
    private AtomicReference<RecordedMessageConverter.AmqpWireMessage> captured;

    @BeforeEach
    void setUp() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);
        registry.register(PingResult.class);

        RecordedMessageConverter converter = new RecordedMessageConverter(registry);
        SessionSerializer sessionSerializer = new SessionSerializer();
        store = new CorrelationStore();
        captured = new AtomicReference<>();

        CommandTransport mockTransport = msg -> {
            captured.set(msg);
            return CompletableFuture.completedFuture(null);
        };

        bus = new CommandBusImpl(
            mockTransport, converter, sessionSerializer, store,
            "TestAdapter", Duration.ofSeconds(30));
    }

    @Test
    void executeAsync_publishesWithCorrectWireFormat() {
        PingCommand cmd = new PingCommand();
        cmd.text = "hello";

        CompletableFuture<PingResult> future = bus.executeAsync(cmd);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().exchange()).isEqualTo("CommandExchange");
        assertThat(captured.get().routingKey()).isEqualTo("TCB.Test.PingCommand");
        assertThat(captured.get().contentType()).isEqualTo("TCB.Test.PingCommand, TCB.Test");
        assertThat(captured.get().correlationId()).isNotEmpty();
        assertThat(store.size()).isEqualTo(1);
        assertThat(future).isNotDone(); // waiting for result
    }

    @Test
    void publish_fireAndForget_doesNotRegisterInStore() {
        PingCommand cmd = new PingCommand();
        cmd.text = "fire";

        String cid = bus.publish(cmd);

        assertThat(cid).isNotEmpty();
        assertThat(captured.get()).isNotNull();
        assertThat(store.size()).isEqualTo(0); // no correlation
    }
}
```

- [ ] **Step 2: Запустить — должен упасть**

- [ ] **Step 3: Написать `CommandBusImpl`**

```java
package ru.tcb.sal.commands.core.bus;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tcb.sal.commands.api.*;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.CommandTransport;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class CommandBusImpl implements CommandBus {

    private static final Logger log = LoggerFactory.getLogger(CommandBusImpl.class);

    private final CommandTransport transport;
    private final RecordedMessageConverter converter;
    private final SessionSerializer sessionSerializer;
    private final CorrelationStore store;
    private final String adapterName;
    private final Duration defaultTimeout;
    private final AtomicLong messageIdCounter = new AtomicLong(1);

    public CommandBusImpl(CommandTransport transport,
                           RecordedMessageConverter converter,
                           SessionSerializer sessionSerializer,
                           CorrelationStore store,
                           String adapterName,
                           Duration defaultTimeout) {
        this.transport = transport;
        this.converter = converter;
        this.sessionSerializer = sessionSerializer;
        this.store = store;
        this.adapterName = adapterName;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public <R extends CommandResult> R execute(CommandWithResult<R> command) {
        return executeAsync(command).join();
    }

    @Override
    public <R extends CommandResult> CompletableFuture<R> executeAsync(CommandWithResult<R> command) {
        return executeAsync(command, defaultTimeout, CommandPriority.NORMAL);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends CommandResult> CompletableFuture<R> executeAsync(
            CommandWithResult<R> command, Duration timeout, CommandPriority priority) {

        String correlationId = UUID.randomUUID().toString();
        Instant expireAt = Instant.now().plus(timeout);

        CompletableFuture<R> future = new CompletableFuture<>();
        store.register(correlationId, new CorrelationStore.Pending(
            correlationId, expireAt, future, command.resultType()));

        RecordedMessage rm = buildRecordedMessage(
            command, correlationId, priority);

        RecordedMessageConverter.AmqpWireMessage wire = converter.toAmqp(rm);

        transport.publish(wire).whenComplete((ack, publishEx) -> {
            if (publishEx != null) {
                store.remove(correlationId);
                future.completeExceptionally(new RuntimeException(
                    "Publish failed: " + publishEx.getMessage(), publishEx));
            }
        });

        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((r, ex) -> {
                if (ex instanceof TimeoutException) {
                    store.remove(correlationId);
                    log.warn("[BUS] Timeout: correlationId={}", correlationId);
                }
            });
    }

    @Override
    public String publish(Command command) {
        String correlationId = UUID.randomUUID().toString();
        RecordedMessage rm = buildRecordedMessage(command, correlationId, CommandPriority.NORMAL);
        RecordedMessageConverter.AmqpWireMessage wire = converter.toAmqp(rm);
        transport.publish(wire);
        log.info("[BUS] Published fire-and-forget: correlationId={} routingKey={}",
            correlationId, rm.routingKey);
        return correlationId;
    }

    private RecordedMessage buildRecordedMessage(Command command, String correlationId,
                                                   CommandPriority priority) {
        String wireName = converter.registry().wireName(command.getClass());
        String assemblyQualified = converter.registry().assemblyQualifiedName(command.getClass());

        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = correlationId;
        rm.exchangeName = "CommandExchange";
        rm.routingKey = wireName;
        rm.contentType = assemblyQualified;
        rm.messageId = String.valueOf(messageIdCounter.incrementAndGet());
        rm.priority = priority.asByte();
        rm.timeStamp = Instant.now();
        rm.sourceServiceId = adapterName;
        rm.acceptLanguage = "ru-RU";
        rm.payload = command;

        rm.additionalData = new LinkedHashMap<>();
        ObjectNode sessionNode = sessionSerializer.mapper().createObjectNode();
        sessionNode.put("SessionId", "");
        sessionNode.put("OperationId", messageIdCounter.get());
        rm.additionalData.put("Session", sessionSerializer.serialize(sessionNode));
        rm.additionalData.put("IsCommand", "");
        rm.additionalData.put("SourceServiceId", adapterName);

        return rm;
    }
}
```

- [ ] **Step 4: Запустить тесты**
- [ ] **Step 5: Коммит**

```bash
git add java-commands/sal-commands-api/ java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add CommandBusImpl with CorrelationStore request/reply"
```

---

## Task 7: CommandHandlerRegistry + BeanPostProcessor

**Files:**
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/handler/HandlerBinding.java`
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/handler/CommandHandlerRegistry.java`
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/handler/CommandHandlerBeanPostProcessor.java`
- Test: `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/handler/CommandHandlerRegistryTest.java`

- [ ] **Step 1: Написать `HandlerBinding`**

```java
package ru.tcb.sal.commands.core.handler;

import ru.tcb.sal.commands.api.annotation.ErrorPolicy;
import java.lang.invoke.MethodHandle;

public record HandlerBinding(
    String wireName,
    Class<?> commandClass,
    Class<?> resultClass,
    Object bean,
    MethodHandle invoker,
    boolean hasContextParam,
    boolean isVoid,
    boolean isAsync,
    ErrorPolicy errorPolicy
) {}
```

- [ ] **Step 2: Написать `CommandHandlerRegistry`**

```java
package ru.tcb.sal.commands.core.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandHandlerRegistry.class);

    private final Map<String, HandlerBinding> bindings = new ConcurrentHashMap<>();

    public void register(HandlerBinding binding) {
        HandlerBinding existing = bindings.putIfAbsent(binding.wireName(), binding);
        if (existing != null) {
            throw new IllegalStateException(
                "Duplicate @CommandHandler for '" + binding.wireName() + "': "
                    + existing.bean().getClass().getName() + " and "
                    + binding.bean().getClass().getName());
        }
        log.info("[REGISTRY] Registered @CommandHandler: '{}' -> {}.{}",
            binding.wireName(), binding.bean().getClass().getSimpleName(),
            binding.invoker());
    }

    public HandlerBinding find(String wireName) {
        return bindings.get(wireName);
    }

    public Collection<HandlerBinding> all() {
        return bindings.values();
    }

    public int size() {
        return bindings.size();
    }
}
```

- [ ] **Step 3: Написать `CommandHandlerBeanPostProcessor`**

```java
package ru.tcb.sal.commands.core.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import ru.tcb.sal.commands.api.CommandContext;
import ru.tcb.sal.commands.api.annotation.CommandHandler;
import ru.tcb.sal.commands.api.annotation.WireName;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

public class CommandHandlerBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandHandlerBeanPostProcessor.class);

    private final CommandHandlerRegistry registry;

    public CommandHandlerBeanPostProcessor(CommandHandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = AopUtils.getTargetClass(bean);
        for (Method method : clazz.getDeclaredMethods()) {
            CommandHandler ann = AnnotationUtils.findAnnotation(method, CommandHandler.class);
            if (ann == null) continue;

            validateAndRegister(bean, method, ann);
        }
        return bean;
    }

    private void validateAndRegister(Object bean, Method method, CommandHandler ann) {
        if (method.getParameterCount() == 0) {
            throw new IllegalStateException(
                "@CommandHandler method must have at least one parameter (the command): "
                    + method.getDeclaringClass().getName() + "." + method.getName());
        }

        Class<?> commandClass = method.getParameterTypes()[0];
        String wireName = ann.value();
        if (wireName.isEmpty()) {
            WireName wn = commandClass.getAnnotation(WireName.class);
            if (wn == null) {
                throw new IllegalStateException(
                    "First parameter of @CommandHandler must have @WireName, or specify value() explicitly: "
                        + method);
            }
            wireName = wn.value();
        }

        boolean hasContext = method.getParameterCount() >= 2
            && CommandContext.class.isAssignableFrom(method.getParameterTypes()[1]);
        boolean isVoid = method.getReturnType() == void.class || method.getReturnType() == Void.class;
        boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());
        Class<?> resultClass = isVoid ? null : method.getReturnType();

        try {
            method.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(bean);

            HandlerBinding binding = new HandlerBinding(
                wireName, commandClass, resultClass, bean, handle,
                hasContext, isVoid, isAsync, ann.onError());

            registry.register(binding);

            log.info("[BPP] Found @CommandHandler: {}.{}() -> '{}'",
                bean.getClass().getSimpleName(), method.getName(), wireName);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access @CommandHandler method: " + method, e);
        }
    }
}
```

- [ ] **Step 4: Написать тест**

```java
package ru.tcb.sal.commands.core.handler;

import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.api.Command;
import ru.tcb.sal.commands.api.CommandResult;
import ru.tcb.sal.commands.api.annotation.WireName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandHandlerRegistryTest {

    @WireName("TCB.Test.PingCommand")
    static class PingCommand implements Command {}
    static class PingResult implements CommandResult {}

    @Test
    void register_and_find() {
        CommandHandlerRegistry registry = new CommandHandlerRegistry();
        HandlerBinding binding = new HandlerBinding(
            "TCB.Test.PingCommand", PingCommand.class, PingResult.class,
            new Object(), null, false, false, false,
            ru.tcb.sal.commands.api.annotation.ErrorPolicy.REPLY_WITH_FAILURE);

        registry.register(binding);

        assertThat(registry.find("TCB.Test.PingCommand")).isNotNull();
        assertThat(registry.find("TCB.Test.PingCommand").commandClass()).isEqualTo(PingCommand.class);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void register_duplicate_throws() {
        CommandHandlerRegistry registry = new CommandHandlerRegistry();
        HandlerBinding b1 = new HandlerBinding("TCB.Test.Dup", PingCommand.class, null,
            new Object(), null, false, false, false,
            ru.tcb.sal.commands.api.annotation.ErrorPolicy.REPLY_WITH_FAILURE);
        HandlerBinding b2 = new HandlerBinding("TCB.Test.Dup", PingCommand.class, null,
            new Object(), null, false, false, false,
            ru.tcb.sal.commands.api.annotation.ErrorPolicy.REPLY_WITH_FAILURE);

        registry.register(b1);
        assertThatThrownBy(() -> registry.register(b2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void find_unknown_returnsNull() {
        CommandHandlerRegistry registry = new CommandHandlerRegistry();
        assertThat(registry.find("unknown")).isNull();
    }
}
```

- [ ] **Step 5: Запустить тесты**
- [ ] **Step 6: Коммит**

```bash
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add @CommandHandler registry + BeanPostProcessor"
```

---

## Task 8: CommandListener + ResultListener

**Files:**
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/listener/CommandListener.java`
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/listener/ResultListener.java`

CommandListener: принимает команды, вызывает handler через `HandlerBinding.invoker()`, строит ответ через `ResponseBuilder`, публикует через `CommandTransport`.

ResultListener: принимает результаты, резолвит `CompletableFuture` в `CorrelationStore`.

- [ ] **Step 1: Написать `CommandListener`**

```java
package ru.tcb.sal.commands.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tcb.sal.commands.core.handler.CommandHandlerRegistry;
import ru.tcb.sal.commands.core.handler.HandlerBinding;
import ru.tcb.sal.commands.core.transport.CommandTransport;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class CommandListener {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final CommandHandlerRegistry handlerRegistry;
    private final RecordedMessageConverter converter;
    private final ResponseBuilder responseBuilder;
    private final CommandTransport transport;
    private final ObjectMapper mapper;

    public CommandListener(CommandHandlerRegistry handlerRegistry,
                            RecordedMessageConverter converter,
                            ResponseBuilder responseBuilder,
                            CommandTransport transport) {
        this.handlerRegistry = handlerRegistry;
        this.converter = converter;
        this.responseBuilder = responseBuilder;
        this.transport = transport;
        this.mapper = converter.mapper();
    }

    public void onMessage(RecordedMessageConverter.AmqpWireMessage amqpMessage) {
        Instant startTime = Instant.now();
        RecordedMessage rm = converter.fromAmqp(amqpMessage);

        String commandType = rm.routingKey;
        log.info("[CMD] Received: type='{}' correlationId={}", commandType, rm.correlationId);

        HandlerBinding binding = handlerRegistry.find(commandType);
        if (binding == null) {
            log.error("[CMD] No handler for '{}'. Dropping.", commandType);
            return;
        }

        String sessionBase64 = rm.additionalData != null ? rm.additionalData.get("Session") : null;

        try {
            Object command = converter.readPayloadAs(rm, binding.commandClass());
            Object result;

            try {
                if (binding.hasContextParam()) {
                    result = binding.invoker().invoke(command, null /* TODO: CommandContext */);
                } else {
                    result = binding.invoker().invoke(command);
                }
            } catch (Throwable t) {
                throw (t instanceof Exception e) ? e : new RuntimeException(t);
            }

            Duration executionDuration = Duration.between(startTime, Instant.now());

            if (binding.isVoid()) {
                log.info("[CMD] Handler completed (void): correlationId={} duration={}ms",
                    rm.correlationId, executionDuration.toMillis());
                return;
            }

            String resultWireName = binding.resultClass() != null
                ? converter.registry().assemblyQualifiedName(binding.resultClass())
                : "System.Object, mscorlib";

            RecordedMessage response = responseBuilder.buildCompleted(
                rm, result, resultWireName, executionDuration, sessionBase64);
            transport.publish(converter.toAmqp(response));

            log.info("[CMD] Sent completed: correlationId={} duration={}ms",
                rm.correlationId, executionDuration.toMillis());

        } catch (Exception e) {
            Duration executionDuration = Duration.between(startTime, Instant.now());
            log.error("[CMD] Handler failed: correlationId={} error='{}'",
                rm.correlationId, e.getMessage(), e);

            RecordedMessage response = responseBuilder.buildFailed(
                rm, e, executionDuration, sessionBase64);
            transport.publish(converter.toAmqp(response));
        }
    }
}
```

- [ ] **Step 2: Написать `ResultListener`**

```java
package ru.tcb.sal.commands.core.listener;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tcb.sal.commands.core.bus.CorrelationStore;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.CommandCompletedEvent;
import ru.tcb.sal.commands.core.wire.CommandFailedEvent;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

public class ResultListener {

    private static final Logger log = LoggerFactory.getLogger(ResultListener.class);

    private final RecordedMessageConverter converter;
    private final CorrelationStore store;

    public ResultListener(RecordedMessageConverter converter, CorrelationStore store) {
        this.converter = converter;
        this.store = store;
    }

    @SuppressWarnings("unchecked")
    public void onMessage(RecordedMessageConverter.AmqpWireMessage amqpMessage) {
        RecordedMessage rm = converter.fromAmqp(amqpMessage);

        CorrelationStore.Pending pending = store.remove(rm.correlationId);
        if (pending == null) {
            log.debug("[RESULT] No pending future for correlationId={}", rm.correlationId);
            return;
        }

        String exchange = rm.exchangeName;
        boolean isCompleted = exchange != null && exchange.contains("CommandCompletedEvent");
        boolean isFailed = exchange != null && exchange.contains("CommandFailedEvent");

        if (isCompleted) {
            try {
                CommandCompletedEvent event = converter.readAsCompleted(rm);
                Object typedResult = converter.readPayloadAs(
                    fakeRm(event.result), pending.expectedResultType());
                pending.future().complete(typedResult);
                log.info("[RESULT] Completed: correlationId={}", rm.correlationId);
            } catch (Exception e) {
                pending.future().completeExceptionally(e);
                log.error("[RESULT] Parse error: correlationId={}", rm.correlationId, e);
            }
        } else if (isFailed) {
            try {
                CommandFailedEvent event = converter.readAsFailed(rm);
                String msg = event.exceptionData != null ? event.exceptionData.message : "Unknown error";
                String code = event.exceptionData != null ? event.exceptionData.code : null;
                pending.future().completeExceptionally(
                    new RuntimeException("[" + code + "] " + msg));
                log.warn("[RESULT] Failed: correlationId={} code={} msg='{}'",
                    rm.correlationId, code, msg);
            } catch (Exception e) {
                pending.future().completeExceptionally(e);
            }
        } else {
            log.warn("[RESULT] Unknown exchange: '{}'", exchange);
            pending.future().completeExceptionally(
                new RuntimeException("Unknown result exchange: " + exchange));
        }
    }

    /** Helper: wrap an object as RecordedMessage.payload for readPayloadAs. */
    private RecordedMessage fakeRm(Object payload) {
        RecordedMessage rm = new RecordedMessage();
        rm.payload = payload;
        return rm;
    }
}
```

- [ ] **Step 3: Коммит**

```bash
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add CommandListener + ResultListener"
```

---

## Task 9: InMemoryCommandTransport + end-to-end тест

**Files:**
- Create: `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/transport/InMemoryCommandTransport.java`
- Test: `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/transport/InMemoryCommandTransportTest.java`

Самый важный тест: полный round-trip `commandBus.execute(cmd)` → handler → result, без RabbitMQ.

- [ ] **Step 1: Написать `InMemoryCommandTransport`**

```java
package ru.tcb.sal.commands.core.transport;

import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory transport for testing. Publishes go directly to registered consumers.
 */
public class InMemoryCommandTransport implements CommandTransport {

    private final Map<String, Consumer<RecordedMessageConverter.AmqpWireMessage>> consumers
        = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> publish(RecordedMessageConverter.AmqpWireMessage message) {
        // Route by exchange name
        Consumer<RecordedMessageConverter.AmqpWireMessage> consumer = consumers.get(message.exchange());
        if (consumer != null) {
            try {
                consumer.accept(message);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    public void registerConsumer(String exchange, Consumer<RecordedMessageConverter.AmqpWireMessage> consumer) {
        consumers.put(exchange, consumer);
    }
}
```

- [ ] **Step 2: Написать end-to-end тест**

```java
package ru.tcb.sal.commands.core.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.api.*;
import ru.tcb.sal.commands.api.annotation.WireName;
import ru.tcb.sal.commands.core.bus.CommandBusImpl;
import ru.tcb.sal.commands.core.bus.CorrelationStore;
import ru.tcb.sal.commands.core.handler.CommandHandlerRegistry;
import ru.tcb.sal.commands.core.handler.HandlerBinding;
import ru.tcb.sal.commands.core.listener.CommandListener;
import ru.tcb.sal.commands.core.listener.ResponseBuilder;
import ru.tcb.sal.commands.core.listener.ResultListener;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCommandTransportTest {

    @WireName(value = "TCB.Test.EchoCommand", assembly = "TCB.Test")
    public static class EchoCommand implements CommandWithResult<EchoResult> {
        public String message;
    }

    @WireName(value = "TCB.Test.EchoResult", assembly = "TCB.Test")
    public static class EchoResult implements CommandResult {
        public String echo;
    }

    // The handler method
    public static EchoResult handleEcho(EchoCommand cmd) {
        EchoResult result = new EchoResult();
        result.echo = "ECHO: " + cmd.message;
        return result;
    }

    private CommandBus bus;

    @BeforeEach
    void setUp() throws Exception {
        WireTypeRegistry wireRegistry = new WireTypeRegistry();
        wireRegistry.register(EchoCommand.class);
        wireRegistry.register(EchoResult.class);

        RecordedMessageConverter converter = new RecordedMessageConverter(wireRegistry);
        SessionSerializer sessionSerializer = new SessionSerializer();
        CorrelationStore store = new CorrelationStore();
        ObjectMapper mapper = converter.mapper();

        InMemoryCommandTransport transport = new InMemoryCommandTransport();

        // Handler side
        CommandHandlerRegistry handlerRegistry = new CommandHandlerRegistry();
        MethodHandle handle = MethodHandles.lookup()
            .findStatic(InMemoryCommandTransportTest.class, "handleEcho",
                java.lang.invoke.MethodType.methodType(EchoResult.class, EchoCommand.class));
        handlerRegistry.register(new HandlerBinding(
            "TCB.Test.EchoCommand", EchoCommand.class, EchoResult.class,
            null, handle, false, false, false,
            ru.tcb.sal.commands.api.annotation.ErrorPolicy.REPLY_WITH_FAILURE));

        ResponseBuilder responseBuilder = new ResponseBuilder(mapper, "TestAdapter");
        CommandListener commandListener = new CommandListener(
            handlerRegistry, converter, responseBuilder, transport);
        ResultListener resultListener = new ResultListener(converter, store);

        // Wire in-memory routing
        transport.registerConsumer("CommandExchange", commandListener::onMessage);
        transport.registerConsumer("TCB.Infrastructure.Command.CommandCompletedEvent",
            resultListener::onMessage);
        transport.registerConsumer("TCB.Infrastructure.Command.CommandFailedEvent",
            resultListener::onMessage);

        bus = new CommandBusImpl(transport, converter, sessionSerializer, store,
            "TestAdapter", Duration.ofSeconds(5));
    }

    @Test
    void fullRoundTrip_executeReturnsResult() {
        EchoCommand cmd = new EchoCommand();
        cmd.message = "Hello World";

        EchoResult result = bus.execute(cmd);

        assertThat(result).isNotNull();
        assertThat(result.echo).isEqualTo("ECHO: Hello World");
    }

    @Test
    void fullRoundTrip_asyncWorks() throws Exception {
        EchoCommand cmd = new EchoCommand();
        cmd.message = "Async";

        EchoResult result = bus.executeAsync(cmd).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.echo).isEqualTo("ECHO: Async");
    }
}
```

- [ ] **Step 3: Запустить тесты**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core -am test
```

Ожидаемое: все тесты зелёные, включая `fullRoundTrip_executeReturnsResult` — команда проходит полный цикл через in-memory transport без RMQ.

- [ ] **Step 4: Коммит**

```bash
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): InMemoryCommandTransport + full round-trip test

End-to-end test proves CommandBus.execute → CommandListener → handler →
ResponseBuilder → ResultListener → CompletableFuture, all in-memory
without RabbitMQ."
```

---

## Self-review checklist

- [ ] `mvn clean verify` из `java-commands/` проходит зелёным
- [ ] InMemoryCommandTransport round-trip тест зелёный
- [ ] CommandBusImpl тесты с mock transport зелёные
- [ ] CorrelationStore тесты зелёные
- [ ] CommandHandlerRegistry тесты зелёные
- [ ] ResponseBuilder тесты зелёные (включая .NET TimeSpan формат)
- [ ] Все коммиты отдельные, не squash'нутые

---

## Что дальше (Plan 2B)

- Exception mapping (`RemoteBusinessException` / `RemoteTechnicalException` + `ExceptionMapper`)
- `SalSession` мутабельная обёртка
- `SalCommandsAutoConfiguration` + `SalCommandsProperties`
- Actuator endpoint + health indicator
- Micrometer metrics + MDC
- `@SalCommandsTest` test-slice
- Logback TurboFilter для `ignorePatterns`
- Документация + release 1.0.0
