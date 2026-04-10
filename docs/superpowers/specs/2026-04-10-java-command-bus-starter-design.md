# Spec: SAL Command Bus — Java Spring Boot Starter v1.0

**Дата:** 2026-04-10
**Scope:** Полный Spring Boot starter для Command Bus подсистемы SAL, мигрированной с .NET на Java.
**Базовый документ:** [`COMMAND_RMQ_FLOW.md`](../../../COMMAND_RMQ_FLOW.md)
**Связанные документы:** `CLAUDE.md` (стандарты проекта)

---

## 1. Цель и контекст

Подсистема SAL Command Bus (`TCB.SAL.Common/Command/CommandProxy.cs` + `TCB.Infrastructure/Command/*`) мигрируется с .NET Framework на Java 21 + Spring Boot 3.x. Результат — семейство Maven-артефактов `sal-commands-*` с автоконфигурируемым Spring Boot starter'ом во главе, готовым к подключению в бизнес-приложения адаптеров.

Миграция идёт **постепенно**: на одном RabbitMQ vhost одновременно живут .NET-адаптеры и Java-адаптеры, которые должны уметь обмениваться командами друг с другом без модификации .NET-стороны. Команды мигрируют «по одной», пока все типы не переедут на Java.

### 1.1 Нефункциональные требования

- Java 21, Spring Boot 3.3.x, Spring AMQP, Jackson.
- Records, sealed interfaces, pattern matching, virtual threads (`spring.threads.virtual.enabled=true`) — из `CLAUDE.md`.
- Constructor injection only. Никакого Lombok. Никакого `@Autowired` на полях.
- Unit tests: JUnit 5 + Mockito. Integration tests: Testcontainers (RabbitMQ).
- Внешние вызовы (RabbitMQ publish) обёрнуты в publisher confirms + таймаут; retry на уровне фреймворка — нет, бизнес ретраит сам.
- Конфигурация — через `application.yml` и `@ConfigurationProperties`. Никакого хардкода в коде.

---

## 2. Принятые решения (scope & constraints)

| # | Решение | Выбор | Последствия |
|---|---|---|---|
| 1 | Scope | Полный стартер v1.0 | 4 Maven-модуля, wire-compat, DLQ, publisher confirms, session, без outbox |
| 2 | Wire compatibility | Полная совместимость с .NET | `RecordedMessage`-JSON байт-в-байт, exchanges `CommandExchange` / `TCB.Infrastructure.Command.CommandCompletedEvent` / `CommandFailedEvent`, `AdditionalData` map, Session как Base64+Deflate, сохранена опечатка `"Confirmtation"` |
| 3 | Type-name mapping | Явная аннотация `@WireName` на всех Command/CommandResult классах | Fail-fast при старте если аннотация отсутствует; ассембли-имя обязательно для результатов |
| 4 | Session propagation | Мутабельное зеркало `SalSession` с .NET-поведением | Handler может читать и менять, delta через `getChangedData()` |
| 5 | Exception handling | Sealed-иерархия `RemoteBusinessException` / `RemoteTechnicalException` + опциональный `ExceptionMapper` реестр | `InfrastructureExceptionDto` — зеркало .NET DTO; оригинальный класс исключения не восстанавливается |
| 6 | Business/Technical criterion | Непустой `Code` → business, override через конфиг-префиксы (`sal.command-bus.errors.business-type-prefixes`) | Retry-логика клиента знает, что ретраить только technical |
| 7 | Legacy features | Confirmation **keep**, Observers **drop**, MBMode → `enabled=false`, Bridge API **drop** | Observers на стороне A — это .NET-only фича; bridge/proxy API добавим по запросу в v1.1 |
| 8 | Durability | Только publisher confirms, без локального outbox | При сбое publish — `RemoteTechnicalException` немедленно; pending futures in-memory, рестарт теряет их |
| 9 | Repo layout | `/mnt/c/work/sal_refactoring/java-commands/` + Maven multi-module | Параллельно с существующими `sal/` и `infrastructure/` .NET каталогами |
| 10 | Dispatch architecture | Собственный correlation-map поверх Spring AMQP | `AsyncRabbitTemplate` несовместим с wire-compat reply topology (именованные queue + binding по AdapterFullName) |
| 11 | Transport layer | Отдельная абстракция `CommandTransport` с `SpringAmqpCommandTransport` и `InMemoryCommandTransport` | Бесплатный test-slice без Testcontainers |

---

## 3. Архитектура

### 3.1 Структура модулей

```
java-commands/                                     (Maven multi-module)
├── pom.xml                                        (spring-boot-starter-parent 3.3.x)
├── sal-commands-api/                              ← публичные контракты, без Spring AMQP
│   └── ru/tcb/sal/commands/api/
│       ├── Command.java                           (marker)
│       ├── CommandResult.java                     (marker)
│       ├── CommandWithResult.java                 (interface<R extends CommandResult>)
│       ├── CommandBus.java
│       ├── CommandContext.java                    (record, без .NET-опечаток)
│       ├── CommandPriority.java                   (enum)
│       ├── SalSession.java                        (mutable session wrapper)
│       ├── annotation/
│       │   ├── CommandHandler.java
│       │   ├── ConfirmatoryCommandHandler.java
│       │   └── WireName.java
│       └── exception/
│           ├── RemoteCommandException.java
│           ├── RemoteBusinessException.java
│           ├── RemoteTechnicalException.java
│           └── CommandTimeoutException.java
│
├── sal-commands-core/                             ← реализация
│   └── ru/tcb/sal/commands/core/
│       ├── transport/
│       │   ├── CommandTransport.java              (interface)
│       │   ├── CommandPublisher.java
│       │   ├── CommandConsumer.java
│       │   └── amqp/
│       │       ├── SpringAmqpCommandTransport.java
│       │       ├── RecordedMessageConverter.java  ← вся wire-магия здесь
│       │       └── AmqpTopologyConfigurer.java
│       ├── wire/
│       │   ├── RecordedMessage.java
│       │   ├── WireCommandContext.java            (с .NET-опечатками Excution*)
│       │   ├── CommandCompletedEvent.java
│       │   ├── CommandFailedEvent.java
│       │   ├── InfrastructureExceptionDto.java
│       │   └── WireTypeRegistry.java
│       ├── handler/
│       │   ├── CommandHandlerRegistry.java
│       │   ├── HandlerBinding.java
│       │   ├── CommandHandlerBeanPostProcessor.java
│       │   ├── ArgumentResolver.java
│       │   └── ReturnAdapter.java
│       ├── bus/
│       │   ├── CommandBusImpl.java
│       │   ├── CorrelationStore.java
│       │   ├── TimeoutWatcher.java
│       │   └── CommandInvocation.java
│       ├── listener/
│       │   ├── CompositeListener.java             (распределяет по типу payload)
│       │   ├── CommandListener.java
│       │   └── ResultListener.java
│       ├── session/
│       │   ├── SessionContextHolder.java
│       │   ├── ThreadLocalSessionHolder.java
│       │   └── SessionSerializer.java             (JSON → Deflate → Base64)
│       ├── exception/
│       │   ├── ExceptionMapper.java
│       │   ├── ExceptionMapperRegistry.java
│       │   └── DefaultExceptionMapper.java
│       └── observability/
│           ├── CommandBusMetrics.java
│           └── CommandBusMdc.java
│
├── sal-commands-spring-boot-starter/              ← autoconfiguration
│   └── ru/tcb/sal/commands/spring/
│       ├── SalCommandsAutoConfiguration.java
│       ├── SalCommandsProperties.java
│       └── actuator/
│           ├── CommandBusHealthIndicator.java
│           └── CommandBusEndpoint.java
│
└── sal-commands-test/                             ← тестовая поддержка
    └── ru/tcb/sal/commands/test/
        ├── SalCommandsTest.java                   (meta-annotation)
        ├── InMemoryCommandTransport.java
        └── SalCommandsTestAutoConfiguration.java
```

### 3.2 Слоевая диаграмма

```
┌─────────────────────────────────────────────────────────┐
│  Пользовательский код: @Component + @CommandHandler    │
│  или inject CommandBus                                  │
├─────────────────────────────────────────────────────────┤
│  sal-commands-api                                       │
│  (Command, CommandBus, CommandContext, аннотации,       │
│   SalSession, exception hierarchy)                      │
├─────────────────────────────────────────────────────────┤
│  handler/ · bus/ · listener/ · exception/ · session/    │
│  (Java-логика, не знает про AMQP)                       │
│                         ▼                                │
│              wire/ (DTO-зеркала .NET)                    │
├─────────────────────────────────────────────────────────┤
│  transport/ (интерфейс CommandTransport)                │
│       ↓                                      ↓          │
│  SpringAmqpCommandTransport       InMemoryCommandTransport │
│  (prod)                            (test)                │
├─────────────────────────────────────────────────────────┤
│  Spring AMQP · CachingConnectionFactory · RabbitTemplate │
└─────────────────────────────────────────────────────────┘
```

**Зависимости модулей:**
```
api ← core ← starter
           ← test
```
Строго однонаправленные. `api` не зависит от Spring даже.

---

## 4. Wire-формат

### 4.1 Зеркало `RecordedMessage`

Центральная структура транспорта. Байт-в-байт совместима с .NET через Jackson (PascalCase):

```java
public final class RecordedMessage {
    public String correlationId;
    public String exchangeName;
    public String routingKey;
    public String sourceServiceId;
    public String messageId;
    public byte priority;
    public Instant timeStamp;
    public Instant expireDate;                     // nullable
    public Object payload;                         // команда / CommandCompletedEvent / CommandFailedEvent
    public Map<String, String> additionalData = new LinkedHashMap<>();
}
```

**Jackson-конфиг** локальный для `RecordedMessageConverter`:

```java
ObjectMapper mapper = new ObjectMapper()
    .setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
    .registerModule(new JavaTimeModule())
    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
```

**Требование wire-compat:** поля пользовательских команд/результатов тоже в PascalCase на проводе. В Java — обычный lowerCamelCase, Jackson конвертирует через политику naming. Никаких `@JsonProperty` на каждом поле.

### 4.2 Зеркало `WireCommandContext`

```java
public final class WireCommandContext {
    public String commandType;                     // FullName
    public String correlationId;
    public String sourceServiceId;
    public Instant timeStamp;
    public Instant expireDate;
    public String excutionServiceId;                // ★ опечатка как в .NET
    public Instant excutionTimeStamp;               // ★ опечатка
    public Duration excutionDuration;               // ★ опечатка
    public byte priority;
    public String sessionId;
    public String operationId;
}
```

Опечатки `Excution*` **сохраняются** — они в .NET DTO, менять нельзя.

Для пользователей API есть «чистый» `api/CommandContext` (record, без опечаток), в который маппится внутренне при вызове handler'а.

### 4.3 `@WireName` и `WireTypeRegistry`

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WireName {
    String value();                                 // FullName, напр. "TCB.Payment.Request.CreatePaymentCommand"
    String assembly() default "";                   // обязательно для результатов
}
```

`WireTypeRegistry` строится при старте из двух источников:
1. Classpath scan через `ClassPathScanningCandidateComponentProvider` — ищет классы с `@WireName`.
2. Явная регистрация через `WireTypeRegistrar` bean (опционально).

**Fail-fast правила:**
- Класс реализует `Command`/`CommandResult`, но без `@WireName` → `BeanInitializationException` с именем класса.
- Два класса с одинаковым `@WireName.value()` → ошибка старта.
- У `CommandResult` без `assembly` → ошибка старта.
- Неизвестный wire-name во **входящем** сообщении → не ошибка старта; сообщение пойдёт в DLQ или через `sendNoHandlerFailure`.

### 4.4 `FullNameWithAssemblyName` для `ResultType`

```java
String render(Class<?> resultClass) {
    String wireName = wireTypeRegistry.wireName(resultClass);        // required
    String assembly = wireTypeRegistry.assembly(resultClass);        // required
    return wireName + ", " + assembly;
}
```

Пример: `"TCB.Payment.Contracts.CreatePaymentResult, TCB.Payment.Contracts"`.

### 4.5 Сериализация payload'а

Один `ObjectMapper` для всех payload'ов внутри `RecordedMessage`. `WireTypeDeserializer` резолвит конкретный тип через `routingKey` (для команд) или через `payload.GetType()` .NET-метку (для Events). Если тип неизвестен — `JsonNode` как fallback, конкретный тип резолвится позже в `CommandBusImpl` когда обрабатывается результат.

### 4.6 Golden JSON тесты

В `sal-commands-core/src/test/resources/golden/` лежит набор JSON-файлов — реальные сообщения из .NET SAL (извлечённые из логов `[SRC -> BUS]` или от руки). `RecordedMessageConverterWireCompatTest` проверяет:

1. **read→write identity:** парсим golden JSON в `RecordedMessage`, сериализуем обратно, сравниваем байт-в-байт.
2. **write→read round-trip:** строим `RecordedMessage` из Java, сериализуем, парсим обратно, проверяем равенство полей.
3. **cross-validation:** запускаем Testcontainers RabbitMQ, публикуем наше сообщение в очередь, считываем через raw `com.rabbitmq.client.Channel`, сравниваем с golden.

---

## 5. Handler discovery и receive path

### 5.1 Аннотации

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommandHandler {
    String value() default "";                      // явный wire-name, или из @WireName параметра
    ErrorPolicy onError() default ErrorPolicy.REPLY_WITH_FAILURE;
}

public enum ErrorPolicy {
    REPLY_WITH_FAILURE,                             // ловим throwable, упаковываем в CommandFailedEvent
    REJECT_TO_DLQ,                                  // AmqpRejectAndDontRequeueException
    REQUEUE                                         // Nack+requeue, для идемпотентных handler'ов
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfirmatoryCommandHandler {
    String value() default "";
}
```

### 5.2 Разрешённые сигнатуры

```java
// Минимальная
@CommandHandler
public CreatePaymentResult createPayment(CreatePaymentCommand cmd) { ... }

// С CommandContext (api/CommandContext — чистый record)
@CommandHandler
public CreatePaymentResult createPayment(CreatePaymentCommand cmd, CommandContext ctx) { ... }

// С SalSession (мутабельная)
@CommandHandler
public CreatePaymentResult createPayment(CreatePaymentCommand cmd, SalSession session) { ... }

// Полная
@CommandHandler
public CreatePaymentResult createPayment(CreatePaymentCommand cmd, SalSession session, CommandContext ctx) { ... }

// Асинхронная
@CommandHandler
public CompletableFuture<CreatePaymentResult> createPayment(CreatePaymentCommand cmd) { ... }

// Fire-and-forget
@CommandHandler
public void logEvent(LogEventCommand cmd) { ... }
```

**Правила resolution:**
1. Первый параметр — команда, помечена `@WireName` (или явный `@CommandHandler.value()`).
2. Доп-параметры резолвятся по типу через `ArgumentResolver`. Неизвестный тип → ошибка старта.
3. Возвращаемое значение:
    - `void`/`Void` → fire-and-forget, результат не отправляется.
    - `R` → синхронно, оборачивается в `CompletableFuture.completedFuture(r)`; вызов в virtual thread.
    - `CompletableFuture<R>` → как есть.
4. Один wire-name → один handler в адаптере. Дубликат → ошибка старта.

### 5.3 `CommandHandlerBeanPostProcessor`

```java
public record HandlerBinding(
    String wireName,
    Class<?> commandClass,
    Class<?> resultClass,                           // null если void
    MethodHandle invoker,                           // bind'нутый к bean
    ArgumentResolver[] argResolvers,
    ReturnAdapter returnAdapter,
    ErrorPolicy errorPolicy,
    boolean isConfirmatory
) {}
```

`postProcessAfterInitialization`:
1. Итерирует `getDeclaredMethods()`, ищет `@CommandHandler`/`@ConfirmatoryCommandHandler`.
2. Валидирует сигнатуру.
3. `MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(bean);`
4. Строит `ArgumentResolver[]` (по параметрам) и `ReturnAdapter` (по возвращаемому типу).
5. Регистрирует в `CommandHandlerRegistry`. Дубликат → `BeanInitializationException`.

**В runtime никакой рефлексии** — только `MethodHandle.invokeExact`.

### 5.4 `CommandListener` (receive path)

Единственный `@RabbitListener` в starter'е, слушает очередь `cmd.<adapterFullName>`, MANUAL ack.

```java
public void onMessage(Message amqpMessage, Channel channel) {
    long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
    try {
        RecordedMessage rm = converter.fromAmqp(amqpMessage);

        // Distribute по типу payload
        if (rm.payload instanceof CommandCompletedEvent || rm.payload instanceof CommandFailedEvent) {
            resultListener.onResult(rm);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Это входящая команда
        HandlerBinding binding = registry.findByWireName(rm.routingKey);
        if (binding == null) {
            sendNoHandlerFailure(rm);
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (rm.expireDate != null && rm.expireDate.isBefore(Instant.now())) {
            channel.basicAck(deliveryTag, false);          // протухло: dst="NUL"
            return;
        }

        executeHandler(rm, binding, channel, deliveryTag);
    } catch (Throwable fatal) {
        log.error("CommandListener fatal", fatal);
        channel.basicReject(deliveryTag, false);
    }
}
```

`executeHandler`:

```java
private void executeHandler(RecordedMessage rm, HandlerBinding binding,
                            Channel channel, long deliveryTag) {
    SalSession session = Optional.ofNullable(rm.additionalData.get("Session"))
        .map(sessionSerializer::deserialize)
        .orElseGet(SalSession::empty);

    CommandContext ctx = CommandContext.incoming(rm, adapterFullName);
    Instant started = clock.instant();
    Object command = converter.readPayload(rm, binding.commandClass());

    try (var sessionScope = sessionHolder.bind(session)) {
        Object[] args = resolve(binding.argResolvers(), command, ctx, session);
        CompletableFuture<?> future = binding.returnAdapter().invoke(binding.invoker(), args);

        future.whenComplete((result, throwable) -> {
            Duration duration = Duration.between(started, clock.instant());
            if (throwable != null) {
                onHandlerFailure(rm, binding, ctx, session, duration, throwable, channel, deliveryTag);
            } else {
                onHandlerSuccess(rm, binding, ctx, session, duration, result, channel, deliveryTag);
            }
        });
    }
}
```

**Ack-стратегия:**
- ACK **после** того, как результат успешно опубликован через publisher confirm.
- Если публикация результата упала → NACK (`REQUEUE`) или Reject в DLQ (по `ErrorPolicy`).
- Handler упал с `REPLY_WITH_FAILURE` и failure опубликован → ACK.
- Handler упал с `REJECT_TO_DLQ` → `basicReject(deliveryTag, false)` → DLQ, отправитель получит таймаут.

### 5.5 `ResultListener`

```java
public void onResult(RecordedMessage rm) {
    CorrelationStore.Pending pending = store.remove(rm.correlationId);
    if (pending == null) return;                    // никто не ждёт (таймаут или дубликат)

    if (rm.payload instanceof CommandCompletedEvent completed) {
        Object typedResult = resolveResult(completed, pending.expectedResultType());
        pending.future().complete(typedResult);
    } else if (rm.payload instanceof CommandFailedEvent failed) {
        RemoteCommandException ex = exceptionMapper.map(failed.exceptionData);
        pending.future().completeExceptionally(ex);
    }
}
```

### 5.6 Топология RabbitMQ

`AmqpTopologyConfigurer` декларирует **.NET-совместимые** имена:

```java
// Exchanges (direct type)
public static final String COMMAND_EXCHANGE = "CommandExchange";
public static final String COMMAND_COMPLETED_EXCHANGE = "TCB.Infrastructure.Command.CommandCompletedEvent";
public static final String COMMAND_FAILED_EXCHANGE = "TCB.Infrastructure.Command.CommandFailedEvent";

// Очередь адаптера (одна на все binding'и)
// name: cmd.<adapterFullName> — именно `cmd.` префикс для дифференцирования от других
String queueName = "cmd." + props.adapterFullName();
```

**Binding'и** создаются **после** того, как все `@CommandHandler`-методы собраны (в `CommandBindingsBootstrap` при `ApplicationReadyEvent`):

```java
@EventListener(ApplicationReadyEvent.class)
public void declareBindings() {
    // Команды: binding от CommandExchange в очередь адаптера по routing key = wireName
    for (HandlerBinding b : registry.all()) {
        rabbitAdmin.declareBinding(BindingBuilder
            .bind(queue).to(commandExchange).with(b.wireName()));
    }

    // Результаты: binding от Completed/Failed в очередь адаптера по routing key = adapterFullName
    rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(completedExchange).with(adapterFullName));
    rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(failedExchange).with(adapterFullName));
}
```

### 5.7 Concurrency

- `spring.threads.virtual.enabled=true` → sync handler'ы не блокируют consumer threads.
- `SimpleMessageListenerContainer.setConcurrentConsumers` из `sal.command-bus.concurrency: 10-20`.
- С virtual threads выделенный thread pool для handler execution **не нужен**; sync handler'ы вызываются в `Thread.ofVirtual().start(...)`.

---

## 6. Send path

### 6.1 Публичный API

```java
public interface CommandBus {
    <R extends CommandResult> R execute(CommandWithResult<R> command);
    <R extends CommandResult> CompletableFuture<R> executeAsync(CommandWithResult<R> command);
    String publish(Command command);
    default <R extends CommandResult> CommandInvocation<R> send(CommandWithResult<R> command) {
        return new CommandInvocation<>(this, command);
    }
}
```

### 6.2 Резолв типа результата

```java
public interface CommandWithResult<R extends CommandResult> extends Command {
    default Class<R> resultType() {
        return (Class<R>) GenericTypeResolver.resolveTypeArgument(getClass(), CommandWithResult.class);
    }
}
```

Резолв кэшируется в `CommandInvocation`. Бизнес может override для динамических случаев.

### 6.3 `CommandBusImpl.doExecute`

```java
<R extends CommandResult> CompletableFuture<R> doExecute(
        CommandWithResult<R> command,
        Duration timeout,
        CommandPriority priority,
        Map<String, String> extraHeaders) {

    String correlationId = UUID.randomUUID().toString();
    Instant expireAt = clock.instant().plus(timeout);

    CompletableFuture<R> future = new CompletableFuture<>();
    store.register(correlationId, new Pending(correlationId, expireAt, future, command.resultType()));

    RecordedMessage rm = buildRecordedMessage(
        command, correlationId, expireAt, priority, extraHeaders);

    transport.publish(rm).whenComplete((ack, publishEx) -> {
        if (publishEx != null) {
            store.remove(correlationId);
            future.completeExceptionally(new RemoteTechnicalException("Publish failed", publishEx));
        }
    });

    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((r, ex) -> {
            if (ex instanceof TimeoutException) store.remove(correlationId);
        });
}
```

**Отличия от .NET `simpleCommandResultHandlers`:**
- Таймаут через `CompletableFuture.orTimeout` — нативный JDK, не нужен свой `TimeoutWatcher`-поток.
- Cleanup через `whenComplete` — гарантированно, без гонок.
- `TimeoutWatcher` второй линии (`@Scheduled(fixedDelay = 30_000)`) пробегает просроченные `Pending` и вычищает их, защищая от memory leak.

### 6.4 `CorrelationStore`

```java
public class CorrelationStore {
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public record Pending(
        String correlationId,
        Instant expireAt,
        CompletableFuture<?> future,
        Class<?> expectedResultType
    ) {}

    public void register(String cid, Pending p) { pending.put(cid, p); }
    public Pending remove(String cid) { return pending.remove(cid); }
    public Collection<Pending> expired(Instant now) { ... }
    public int size() { return pending.size(); }
}
```

### 6.5 `SpringAmqpCommandTransport.publish`

```java
@Override
public CompletableFuture<Void> publish(RecordedMessage rm) {
    CorrelationData correlationData = new CorrelationData(rm.correlationId);
    CompletableFuture<CorrelationData.Confirm> confirmFuture = correlationData.getFuture();

    Message amqpMessage = converter.toAmqp(rm);
    rabbitTemplate.send(rm.exchangeName, rm.routingKey, amqpMessage, correlationData);

    return confirmFuture
        .orTimeout(publishConfirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .thenCompose(confirm -> {
            if (confirm.isAck()) return CompletableFuture.completedFuture(null);
            return CompletableFuture.failedFuture(
                new RemoteTechnicalException("Publisher nack: " + confirm.getReason()));
        });
}
```

Publisher confirms включены через `spring.rabbitmq.publisher-confirm-type: correlated` в Spring Boot config.

### 6.6 `CommandInvocation` (fluent builder)

```java
public final class CommandInvocation<R extends CommandResult> {
    private final CommandBusImpl bus;
    private final CommandWithResult<R> command;
    private Duration timeout;
    private CommandPriority priority = CommandPriority.NORMAL;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public CommandInvocation<R> withTimeout(Duration t) { this.timeout = t; return this; }
    public CommandInvocation<R> withPriority(CommandPriority p) { this.priority = p; return this; }
    public CommandInvocation<R> withHeader(String k, String v) { headers.put(k, v); return this; }

    public R execute() { return executeAsync().join(); }
    public CompletableFuture<R> executeAsync() {
        return bus.doExecute(command, effectiveTimeout(), priority, headers);
    }
}
```

**Без RetryPolicy в v1.0.** Retry — на уровне бизнес-кода через Spring Retry, если нужен.

---

## 7. Exception handling

### 7.1 Иерархия исключений (api/)

```java
public sealed abstract class RemoteCommandException extends RuntimeException
    permits RemoteBusinessException, RemoteTechnicalException {

    private final String exceptionType;             // полное имя класса с удалённой стороны
    private final String code;                      // nullable
    private final String codeDescription;           // nullable
    private final String adapterName;
    private final Instant remoteTimeStamp;
    private final Object properties;                // raw properties из DTO
    private final RemoteCommandException remoteCause;    // из InnerException

    // getters...
}

public final class RemoteBusinessException extends RemoteCommandException { ... }
public final class RemoteTechnicalException extends RemoteCommandException { ... }

public final class CommandTimeoutException extends RemoteTechnicalException { ... }
```

### 7.2 `DefaultExceptionMapper`

```java
public class DefaultExceptionMapper implements ExceptionMapper {
    private final SalCommandsProperties.Errors config;
    private final List<Pattern> businessPatterns;                // compiled once

    @Override
    public Optional<RemoteCommandException> map(InfrastructureExceptionDto dto) {
        RemoteCommandException cause = dto.innerException != null
            ? map(dto.innerException).orElse(null) : null;

        boolean isBusiness = isBusiness(dto);

        RemoteCommandException ex = isBusiness
            ? new RemoteBusinessException(dto, cause)
            : new RemoteTechnicalException(dto, cause);
        return Optional.of(ex);
    }

    private boolean isBusiness(InfrastructureExceptionDto dto) {
        if (dto.code != null && !dto.code.isBlank()) return true;
        return businessPatterns.stream().anyMatch(p -> p.matcher(dto.exceptionType).matches());
    }
}
```

### 7.3 `ExceptionMapperRegistry`

Пользовательский код может объявить свой `ExceptionMapper` bean для конкретных Code/ExceptionType — он будет опрошен первым. `DefaultExceptionMapper` — fallback в конце.

```java
public class ExceptionMapperRegistry {
    private final List<ExceptionMapper> userMappers;
    private final DefaultExceptionMapper fallback;

    public RemoteCommandException map(InfrastructureExceptionDto dto) {
        return userMappers.stream()
            .flatMap(m -> m.map(dto).stream())
            .findFirst()
            .orElseGet(() -> fallback.map(dto).orElseThrow());
    }
}
```

### 7.4 Исходящая упаковка исключений handler'а

При `throwable` из handler'а `CommandListener` строит `InfrastructureExceptionDto`:

```java
private InfrastructureExceptionDto buildDto(Throwable ex) {
    InfrastructureExceptionDto dto = new InfrastructureExceptionDto();
    dto.exceptionType = ex.getClass().getName();
    dto.message = ex.getMessage();
    dto.adapterName = adapterFullName;
    dto.sourceType = ex.getStackTrace().length > 0 ? ex.getStackTrace()[0].getClassName() : null;
    dto.sourcePath = ex.getStackTrace().length > 0 ? ex.getStackTrace()[0].toString() : null;
    dto.sessionId = sessionHolder.current().getSessionId();
    dto.timeStamp = clock.instant();
    dto.properties = Map.of("stackTrace", ExceptionUtils.getStackTrace(ex));

    if (ex instanceof BusinessException business) {
        dto.code = business.getCode();
        dto.codeDescription = business.getCodeDescription();
    }

    if (ex.getCause() != null) {
        dto.innerException = buildDto(ex.getCause());
    }
    return dto;
}
```

`BusinessException` — пользовательское базовое исключение (не в starter'е; бизнес пишет сам). Если handler бросил обычный `RuntimeException` без `Code` — DTO едет с пустым `Code` → на стороне отправителя это `RemoteTechnicalException`.

---

## 8. Session handling

### 8.1 `SalSession` API (api/)

```java
public final class SalSession {
    private final ObjectNode data;
    private final Set<String> changedKeys = new LinkedHashSet<>();

    public static SalSession empty() { ... }
    public static SalSession from(ObjectNode data) { ... }

    // Обязательные поля
    public String getSessionId()    { return text("SessionId", ""); }
    public long   getOperationId()  { return number("OperationId", 0L); }
    public Long   getHierarchyId()  { return nullableLong("HierarchyId"); }
    public Long   getAuthId()       { return nullableLong("AuthId"); }

    // Свободный доступ
    public <T> Optional<T> tryGetValue(String key, Class<T> type) { ... }
    public <T> T getValue(String key, Class<T> type) { ... }
    public <T> T getSafeValue(String key, T defaultValue) { ... }

    // Мутация
    public void addOrUpdate(String key, Object value) { ... }
    public void remove(String key) { ... }

    // Delta
    public boolean isChanged() { return !changedKeys.isEmpty(); }
    public ObjectNode getChangedData() { ... }        // { "updateValues": {...}, "removeValues": [...] }
    public ObjectNode snapshot() { return data.deepCopy(); }
}
```

Поведенческое зеркало `.NET Session.cs`.

### 8.2 `SessionSerializer` — wire-формат

```java
public String serialize(SalSession session) {
    byte[] json = mapper.writeValueAsBytes(session.snapshot());
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflate = new DeflaterOutputStream(bos)) {
        deflate.write(json);
    }
    return Base64.getEncoder().encodeToString(bos.toByteArray());
}

public SalSession deserialize(String base64) {
    try {
        byte[] compressed = Base64.getDecoder().decode(base64);
        try (InflaterInputStream inflate = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            ObjectNode node = (ObjectNode) mapper.readTree(inflate);
            return SalSession.from(node);
        }
    } catch (IOException e) {
        log.warn("Failed to deserialize session, returning empty", e);
        return SalSession.empty();                   // .NET fallback для corrupted session
    }
}
```

Алгоритм: `session → JSON (PascalCase) → Deflate raw → Base64`. `.NET` `DeflateStream` совместим с Java `DeflaterOutputStream` (без gzip-wrapper).

### 8.3 `SessionContextHolder` — bind/unbind

```java
public interface SessionContextHolder {
    SalSession current();                            // empty() если не bind'нуто
    AutoCloseable bind(SalSession session);          // try-with-resources
}

public class ThreadLocalSessionHolder implements SessionContextHolder {
    private final ThreadLocal<SalSession> holder = new ThreadLocal<>();

    @Override public SalSession current() {
        SalSession s = holder.get();
        return s != null ? s : SalSession.empty();
    }

    @Override public AutoCloseable bind(SalSession session) {
        SalSession previous = holder.get();
        holder.set(session);
        return () -> {
            if (previous != null) holder.set(previous);
            else holder.remove();
        };
    }
}
```

`try-with-resources` в `CommandListener` гарантирует очистку после обработки — решает известную «находку №8 утечки сессии» из .NET CODE_REVIEW.

### 8.4 Confirmation flow

```java
@ConfirmatoryCommandHandler
public ConfirmationResult confirmTransfer(ConfirmTransferCommand cmd, SalSession session) {
    if (!hasValidConfirmationCode(cmd.code())) {
        return ConfirmationResult.required("Enter SMS code");
    }
    return ConfirmationResult.completed();
}
```

`CommandListener` при получении команды смотрит `additionalData.get("Confirmtation")`:
- Ключ присутствует → ищет `@ConfirmatoryCommandHandler`; если найден — вызывает, иначе fallback в `@CommandHandler`.
- Ключа нет → только обычный handler.

**Упрощение v1.0:** умеем **принимать** confirmation-команды, но не отправлять их из Java. Исходящее направление — в v1.1 по запросу.

---

## 9. Configuration

### 9.1 `SalCommandsProperties`

```java
@ConfigurationProperties(prefix = "sal.command-bus")
@Validated
public record SalCommandsProperties(
    boolean enabled,
    @NotBlank String adapterFullName,               // → queue name, SourceServiceId
    @DurationMin(seconds = 1) Duration defaultTimeout,
    @DurationMin(millis = 100) Duration publishConfirmTimeout,
    @NotBlank String concurrency,                    // "10-20"
    boolean priorityEnabled,
    @Min(1) @Max(255) int maxPriority,
    @Valid Errors errors,
    @Valid Logging logging,
    @Valid DeadLetter deadLetter
) {
    public record Errors(
        List<String> businessTypePrefixes            // regex
    ) {}

    public record Logging(
        boolean logFullPayload,
        @Min(1) int truncateAt,
        List<String> ignorePatterns                  // regex по wire-name
    ) {}

    public record DeadLetter(
        boolean enabled,
        @NotBlank String exchange                    // default "sal.commands.dlx"
    ) {}
}
```

### 9.2 Пример `application.yml`

```yaml
sal:
  command-bus:
    enabled: true
    adapter-full-name: ru.tcb.payment.JavaPaymentAdapter
    default-timeout: 120s
    publish-confirm-timeout: 5s
    concurrency: 10-20
    priority-enabled: true
    max-priority: 10
    errors:
      business-type-prefixes:
        - "^TCB\\..+\\.Business\\..+"
    logging:
      log-full-payload: false
      truncate-at: 512
      ignore-patterns:
        - ".*HeartbeatCommand$"
    dead-letter:
      enabled: true
      exchange: sal.commands.dlx

spring:
  threads:
    virtual:
      enabled: true
  rabbitmq:
    host: ${RMQ_HOST:localhost}
    username: ${RMQ_USER}
    password: ${RMQ_PASS}
    publisher-confirm-type: correlated
```

**Фиксированные значения** (не в properties, а как константы в `AmqpTopologyConfigurer`):
- `CommandExchange` — основной exchange для входящих команд.
- `TCB.Infrastructure.Command.CommandCompletedEvent` — для успешных результатов.
- `TCB.Infrastructure.Command.CommandFailedEvent` — для неуспешных результатов.

Это жёсткий контракт с .NET-миром; override появится только при реальной необходимости.

---

## 10. Observability

### 10.1 Метрики (Micrometer)

| Метрика | Тип | Теги |
|---|---|---|
| `sal.commands.sent` | Counter | `command.type`, `outcome` = {`success`, `failure`, `timeout`, `publish_failed`} |
| `sal.commands.received` | Counter | `command.type`, `outcome` = {`success`, `business_failure`, `technical_failure`, `no_handler`, `expired`} |
| `sal.commands.execution.duration` | Timer | `command.type` |
| `sal.commands.pending` | Gauge | — (размер `CorrelationStore`) |
| `sal.commands.publish.confirm.duration` | Timer | — |

Все метрики включаются только если в classpath есть `MeterRegistry` (`@ConditionalOnClass`).

### 10.2 MDC (SLF4J)

Автоматически проставляются в `CommandListener` и `CommandBusImpl`:

- `correlationId` — на весь жизненный цикл
- `commandType` — wire-name команды
- `direction` — `OUT` / `IN`
- `sourceServiceId` — от кого пришла (receive side)

Очистка MDC гарантирована `try-finally`.

### 10.3 Actuator endpoints

- `/actuator/health/commandBus` — статус RMQ connection (`UP`/`DOWN`) + количество handler'ов
- `/actuator/commandBus` (custom endpoint) — список всех `HandlerBinding` с wire-name, классом метода, политикой ошибок

### 10.4 Logback TurboFilter

`sal.command-bus.logging.ignore-patterns` — список regex по wire-name. TurboFilter смотрит в MDC `commandType` и глушит лог-события для матчинга.

---

## 11. Testing

### 11.1 `sal-commands-test` — test slice

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@BootstrapWith(SpringBootTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@ImportAutoConfiguration({
    SalCommandsTestAutoConfiguration.class,
    JacksonAutoConfiguration.class
})
public @interface SalCommandsTest {
    Class<?>[] handlers() default {};
}
```

`SalCommandsTestAutoConfiguration` подменяет `SpringAmqpCommandTransport` на `InMemoryCommandTransport`.

### 11.2 `InMemoryCommandTransport`

Реализует `CommandTransport`, публикация идёт **напрямую** в локальный dispatcher, который через `CommandHandlerRegistry` находит handler и вызывает в том же JVM. Результат возвращается через `CorrelationStore` тем же callback путём.

**Семантическая совместимость с реальным транспортом:**
- Ошибки упаковываются в `InfrastructureExceptionDto` так же.
- Session проходит через `SessionSerializer` round-trip (чтобы ловить проблемы Base64/Deflate).
- Dispatching через wire-name, как в prod.

### 11.3 Пример теста

```java
@SalCommandsTest(handlers = PaymentCommandHandlers.class)
class PaymentCommandHandlersTest {

    @Autowired CommandBus commandBus;
    @MockBean PaymentService paymentService;

    @Test
    void createPayment_happyPath() {
        when(paymentService.create(any(), any())).thenReturn(new Payment("pay-42"));

        CreatePaymentResult result = commandBus.execute(
            new CreatePaymentCommand(BigDecimal.TEN, "order-1"));

        assertThat(result.paymentId()).isEqualTo("pay-42");
    }

    @Test
    void businessFailure_throwsRemoteBusinessException() {
        when(paymentService.create(any(), any()))
            .thenThrow(new BusinessException("PAYMENT_DECLINED", "Insufficient funds"));

        assertThatThrownBy(() -> commandBus.execute(new CreatePaymentCommand(BigDecimal.ZERO, "order-1")))
            .isInstanceOf(RemoteBusinessException.class)
            .hasFieldOrPropertyWithValue("code", "PAYMENT_DECLINED");
    }
}
```

### 11.4 Wire-compat integration tests (internal)

Отдельно от `sal-commands-test` — internal тесты в `sal-commands-core/src/test/integration/`, запускаются с Testcontainers RabbitMQ. Проверяют:

1. Golden JSON round-trip с реальной шиной.
2. Два Java-адаптера обмениваются командами end-to-end через Testcontainers RMQ.
3. (Если доступен .NET-стенд) Java → .NET и .NET → Java манипуляции через тот же vhost.

---

## 12. План имплементации

### Этап 1: Модули и скелет (0.5 дня)
- Родительский `pom.xml`, четыре подмодуля.
- Пустые классы в package'ах (компилируется).
- Smoke-тест: starter грузится в пустом Spring Boot приложении.

### Этап 2: Wire-слой (2-3 дня)
- `RecordedMessage`, `WireCommandContext`, `CommandCompletedEvent`, `CommandFailedEvent`, `InfrastructureExceptionDto`.
- `WireTypeRegistry` + `@WireName` + classpath scan.
- `RecordedMessageConverter` с Jackson-конфигом.
- `SessionSerializer` + round-trip unit-тесты.
- Golden JSON тесты.

**Deliverable:** Converter принимает байты .NET-сообщения и выдаёт Java-объекты и наоборот.

### Этап 3: Transport-абстракция (1-2 дня)
- `CommandTransport`, `CommandPublisher`, `CommandConsumer` — интерфейсы.
- `SpringAmqpCommandTransport` — publish через `RabbitTemplate` + publisher confirms.
- `AmqpTopologyConfigurer` — декларация exchanges/queue/bindings.
- `InMemoryCommandTransport` — для тестов.

**Deliverable:** Оба транспорта проходят один `CommandTransportContractTest`.

### Этап 4: Handler discovery и dispatching (2-3 дня)
- `@CommandHandler`, `@ConfirmatoryCommandHandler`, `HandlerBinding`, `CommandHandlerRegistry`.
- `CommandHandlerBeanPostProcessor`.
- `ArgumentResolver`, `ReturnAdapter`.
- `CommandListener`, `ResultListener`, `CompositeListener`.

**Deliverable:** Handler с `@CommandHandler` регистрируется, сообщение приходит, dispatch работает через `InMemoryCommandTransport`.

### Этап 5: Send path (2 дня)
- `CommandBus` + `CommandBusImpl`.
- `CorrelationStore` + `TimeoutWatcher`.
- `CommandInvocation` fluent builder.
- `SalSession` + `ThreadLocalSessionHolder`.
- Unit-тесты: happy path, timeout, publish-failure, протухшее сообщение.

**Deliverable:** Адаптер отправляет команду и принимает результат через `InMemoryCommandTransport`.

### Этап 6: Exception handling (1 день)
- `RemoteCommandException` hierarchy.
- `DefaultExceptionMapper` + `ExceptionMapperRegistry`.
- Упаковка outgoing error в `InfrastructureExceptionDto`.

**Deliverable:** Ошибки в обе стороны с правильным business/technical маппингом.

### Этап 7: Starter autoconfiguration (1 день)
- `SalCommandsAutoConfiguration` с conditional beans.
- `SalCommandsProperties` + валидация.
- `AutoConfiguration.imports` файл.
- Actuator endpoint + health indicator.
- Micrometer metrics + MDC.
- Logback TurboFilter для `ignorePatterns`.

**Deliverable:** `<dependency>sal-commands-spring-boot-starter</dependency>` — минимальное приложение получает работающий `CommandBus` bean.

### Этап 8: Test-slice (1 день)
- `@SalCommandsTest` meta-аннотация.
- `SalCommandsTestAutoConfiguration`.
- Примеры тестов.

**Deliverable:** Публикуемый `sal-commands-test` модуль.

### Этап 9: Wire-compat integration test (1-2 дня)
- Testcontainers RabbitMQ.
- Two-adapter E2E через реальную шину.
- Golden-тест read/write через реальную шину.

**Deliverable:** Доказано, что wire-compat работает.

### Этап 10: Документация и релиз 1.0.0 (0.5-1 день)
- README в каждом модуле.
- Основной README с quick-start и ссылками.
- CHANGELOG.md.
- Publish в корпоративный Nexus (или локальный SNAPSHOT).

### 12.1 Критические зависимости

```
Этап 1 (skeleton)
  └── Этап 2 (wire)
        └── Этап 3 (transport)
              └── Этап 4 (handler discovery)
                    ├── Этап 5 (send path)
                    │     └── Этап 6 (exceptions)
                    │           └── Этап 7 (starter autoconfig)
                    │                 └── Этап 8 (test-slice)
                    └── Этап 9 (wire-compat integration) ← от 4,5,7
                          └── Этап 10 (docs/release)
```

### 12.2 Суммарный бюджет

~12-16 рабочих дней при работе одного разработчика.

---

## 13. Явно отсутствующее в v1.0

- **Outbox** — ни JDBC, ни Redis. Публикация через publisher confirms, при сбое — исключение.
- **RetryPolicy** на уровне фреймворка. Retry делается бизнес-кодом через Spring Retry.
- **Observer result handlers** (`ICommandResultHandler` / `ICommonCommandResultHandler`) — это .NET-only фича, observer'ы делаются через Spring `ApplicationEventPublisher`.
- **Bridge API** (`PublishCommandResult(RecordedMessage, ...)`) — добавится по запросу, не часть v1.0.
- **Исходящие confirmation-команды** — умеем только принимать.
- **Recovery pending futures** при рестарте — in-memory, теряются. Бизнес обязан ретраить при необходимости.
- **Hot-регистрация handler'ов** — реестр неизменяем после `ApplicationReadyEvent`. В .NET SAL `RegisterCommand` мог пересоздать подписку — здесь нет (решает известную .NET race condition `RegisterCommand` vs `CreateCommandSubscription`).
- **HTTP pipeline, ActionProvider, WatchDog, Self.Client** — всё это .NET-naследие, в RMQ-only мире не нужно.

---

## 14. Связанные документы

- [`COMMAND_RMQ_FLOW.md`](../../../COMMAND_RMQ_FLOW.md) — полный анализ .NET реализации с алгоритмами и миграционным гайдом.
- [`CLAUDE.md`](../../../CLAUDE.md) — стандарты проекта: Java 21, Spring Boot, virtual threads, constructor injection, Testcontainers.
