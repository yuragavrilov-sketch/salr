# SAL Commands — Архитектура

> Этот документ предназначен для LLM/RAG-контекста и содержит всю необходимую информацию для понимания системы без чтения исходного кода.

## Назначение

Java Spring Boot starter, реализующий Command Bus подсистему TCB.SAL. Позволяет Java-адаптерам отправлять и принимать команды через RabbitMQ в формате, полностью совместимом с существующими .NET-адаптерами (TCB.SAL.Common). Оба типа адаптеров работают на одном RabbitMQ vhost одновременно.

## Слоевая архитектура

```
┌─────────────────────────────────────────────────────┐
│  Бизнес-код: @CommandHandler методы / CommandBus     │
├─────────────────────────────────────────────────────┤
│  sal-commands-api                                    │
│  CommandBus, @CommandHandler, @WireName,             │
│  CommandContext, SalSession, exception hierarchy      │
├─────────────────────────────────────────────────────┤
│  sal-commands-core                                   │
│  CommandBusImpl, CorrelationStore, TimeoutWatcher     │
│  CommandHandlerRegistry, BeanPostProcessor            │
│  CommandListener, ResultListener, ResponseBuilder     │
│  RecordedMessageConverter, SessionSerializer          │
│  Wire DTOs (RecordedMessage, Events, Context)         │
├─────────────────────────────────────────────────────┤
│  transport/ (интерфейс CommandTransport)              │
│       ↓                          ↓                    │
│  SpringAmqpTransport       InMemoryTransport          │
│  (production)              (tests)                    │
├─────────────────────────────────────────────────────┤
│  Spring AMQP + RabbitMQ                               │
└─────────────────────────────────────────────────────┘
```

## Ключевые компоненты

### Send path (отправка команды)

```
Бизнес-код
  → CommandBus.execute(cmd)
    → CommandBusImpl: buildRecordedMessage()
      → WireTypeRegistry: wireName + assemblyQualifiedName
      → SessionSerializer: serialize session → BinaryWriter + Deflate + Base64
      → RecordedMessage (internal struct)
    → RecordedMessageConverter.toAmqp(rm)
      → AmqpWireMessage(body=JSON команды, props, headers)
    → CommandTransport.publish(wire)
      → SpringAmqpCommandTransport → RabbitTemplate.send()
    → CorrelationStore.register(correlationId, CompletableFuture)
    → CompletableFuture.orTimeout(timeout)
  → await result
```

### Receive path (приём результата)

```
RabbitMQ → SimpleMessageListenerContainer
  → RecordedMessageConverter.fromSpringMessage(msg)
    → AmqpWireMessage
  → ResultListener.onMessage(wire)
    → converter.fromAmqp(wire) → RecordedMessage
    → CorrelationStore.remove(correlationId)
    → converter.readAsCompleted(rm) → CommandCompletedEvent
    → converter.readPayloadAs(result, expectedType)
    → pending.future.complete(typedResult)
  → CompletableFuture разблокируется
  → commandBus.execute() возвращает типизированный результат
```

### Handler path (обработка входящей команды)

```
RabbitMQ → SimpleMessageListenerContainer
  → CommandListener.onMessage(wire)
    → converter.fromAmqp(wire) → RecordedMessage
    → CommandHandlerRegistry.find(routingKey)
    → converter.readPayloadAs(rm, binding.commandClass)
    → binding.invoker.invoke(command) → result
    → ResponseBuilder.buildCompleted(rm, result, wireName, duration, session)
    → converter.toAmqp(responseRm)
    → CommandTransport.publish(responseWire)
  → .NET-адаптер получает CommandCompletedEvent
```

### Handler discovery (при старте)

```
Spring Boot start
  → CommandHandlerBeanPostProcessor.postProcessAfterInitialization()
    → scan all beans for @CommandHandler methods
    → validate signature: first param = command class with @WireName
    → build MethodHandle via MethodHandles.lookup().unreflect().bindTo(bean)
    → CommandHandlerRegistry.register(HandlerBinding)
  → CommandBindingsBootstrap (в starter autoconfig)
    → для каждого HandlerBinding: declare queue Command_<wireName>
    → bind queue к CommandExchange с routing key = wireName
```

## Wire формат

> Подробности в [WIRE_FORMAT.md](WIRE_FORMAT.md)

Критические находки (не документированы в исходном .NET коде, обнаружены экспериментально):

1. **RecordedMessage НЕ сериализуется как JSON** — metadata в AMQP properties/headers, body = только payload
2. **Session = BinaryWriter(7BitEncodedInt + UTF8) → raw Deflate(nowrap=true) → Base64** — не JSON
3. **Java Deflater должен использовать nowrap=true** — .NET DeflateStream = raw DEFLATE без zlib header
4. **Очереди handler'ов** = `Command_<FullName>` (не `cmd.<adapterName>`)
5. **Priority** в Context = строка `"Normal"` (не byte)
6. **Duration** в Context = .NET TimeSpan `"00:00:00.1234567"` (не ISO PT)
7. **content_type** AMQP property = .NET AssemblyQualifiedName (не MIME-type)

## Модули Maven

```
sal-commands-parent (pom, spring-boot-starter-parent:3.3.5)
├── sal-commands-api          ← публичный контракт, без Spring
├── sal-commands-core         ← реализация, spring-context + jackson + spring-amqp(optional)
├── sal-commands-spring-boot-starter  ← autoconfig + properties
├── sal-commands-test         ← @SalCommandsTest + InMemoryTransport
├── sal-commands-demo-app     ← web UI для E2E тестирования
├── sal-commands-example      ← минимальный пример
└── sal-commands-wire-probe   ← CLI для захвата RMQ трафика
```

Зависимости (строго однонаправленные):
```
api ← core ← starter
           ← test
```

## Аннотации

| Аннотация | Target | Где |
|---|---|---|
| `@WireName(value, assembly)` | TYPE | На классах Command/CommandResult — привязка к .NET FullName |
| `@CommandHandler(value, onError)` | METHOD | На методах-обработчиках в @Component |

## Конфигурация

```yaml
sal:
  command-bus:
    enabled: true              # default true
    adapter-name: MyAdapter    # имя в шине, default "JavaAdapter"
    default-timeout: 120s      # таймаут execute(), default 120s
    concurrency: "10"          # потоки consumer'а, default "10"
```

## Топология RabbitMQ

| Exchange | Тип | Назначение |
|---|---|---|
| `CommandExchange` | direct | Доставка команд A→B |
| `TCB.Infrastructure.Command.CommandCompletedEvent` | direct | Успешные результаты B→A |
| `TCB.Infrastructure.Command.CommandFailedEvent` | direct | Ошибки B→A |

| Очередь | Bindings |
|---|---|
| `cmd.<adapterName>` | Completed + Failed exchanges по routing key = adapterName |
| `Command_<commandWireName>` | CommandExchange по routing key = wireName (по одной на handler) |

## Обработка ошибок

Sealed hierarchy:
```
RemoteCommandException (abstract, sealed)
├── RemoteBusinessException (final) — code != null/blank
└── RemoteTechnicalException (final) — code == null/blank
```

ExceptionMapper: `InfrastructureExceptionDto → RemoteCommandException` по наличию `code`.

## Тестирование

```java
@SalCommandsTest
class MyTest {
    @Autowired CommandBus commandBus;
    // InMemoryCommandTransport — полный pipeline без RMQ
}
```

InMemoryCommandTransport маршрутизирует по exchange name напрямую в CommandListener/ResultListener. Полный round-trip `execute → handler → result` за миллисекунды.

## Ключевые классы и размеры

| Класс | LOC | Роль |
|---|---|---|
| RecordedMessageConverter | 219 | AMQP ↔ RecordedMessage конвертер, ядро wire-слоя |
| SessionSerializer | 142 | BinaryWriter + Deflate + Base64, .NET-совместимая сессия |
| CommandBusImpl | 131 | Send path: build → publish → correlate → timeout |
| ResponseBuilder | 117 | Сборка CommandCompletedEvent/CommandFailedEvent в .NET формате |
| SalSession | 109 | Мутабельная обёртка сессии (зеркало .NET Session.cs) |
| CommandListener | 92 | Receive path: parse → dispatch → handler → send result |
| WireTypeRegistry | 81 | Bidirectional Class ↔ wireName реестр |
| CommandHandlerBeanPostProcessor | 75 | Scan → validate → MethodHandle → register |
| ResultListener | 73 | Receive results → resolve CompletableFuture |

## История решений

| Дата | Решение | Причина |
|---|---|---|
| 2026-04-10 | Полная wire-совместимость с .NET | Постепенная миграция: Java и .NET адаптеры на одном vhost |
| 2026-04-10 | Явная @WireName аннотация | Wire-имена — жёсткий контракт, нельзя выводить конвенцией |
| 2026-04-10 | Мутабельная SalSession | .NET handler'ы мутируют сессию, Java должна уметь так же |
| 2026-04-10 | Только publisher confirms, без outbox | Минимальная сложность для v1.0 |
| 2026-04-13 | RecordedMessage = AMQP props/headers + raw body | Обнаружено экспериментально на реальном стенде |
| 2026-04-13 | Session = BinaryWriter, не JSON | Обнаружено через crash на .NET стороне |
| 2026-04-13 | Deflate nowrap=true | Java default = zlib wrapper, .NET = raw DEFLATE |
| 2026-04-13 | Queue naming = Command_<FullName> | .NET SAL convention, обнаружено при тестировании обратного направления |
