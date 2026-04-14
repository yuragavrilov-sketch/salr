# SAL Commands — Java Spring Boot Starter

Java-порт подсистемы Command Bus из .NET TCB.SAL.Common. Полная wire-совместимость с .NET-адаптерами: Java и .NET адаптеры обмениваются командами через один RabbitMQ vhost без модификации .NET-стороны.

## Состояние проекта

| Что | Статус |
|---|---|
| Wire-совместимость с .NET (оба направления) | Проверено на реальном стенде |
| `CommandBus.execute()` / `executeAsync()` / `publish()` | Работает |
| `@CommandHandler` discovery через BeanPostProcessor | Работает |
| Auto-декларация `Command_<FullName>` очередей для handler'ов | Работает |
| Request/reply через CorrelationStore + CompletableFuture | Работает |
| InMemoryCommandTransport для тестов | Работает |
| Spring Boot AutoConfiguration | Готово |
| `@SalCommandsTest` test-slice | Готово |
| 40 unit-тестов | Зеленые |
| CVE scan (Trivy) | 0 CRITICAL/HIGH |

## Быстрый старт

### 1. Подключить зависимость

```xml
<dependency>
    <groupId>ru.copperside.sal</groupId>
    <artifactId>sal-commands-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Конфигурация

```yaml
sal:
  command-bus:
    adapter-name: MyJavaAdapter       # имя этого адаптера в шине
    default-timeout: 120s             # таймаут по умолчанию
    concurrency: "10"                 # потоки consumer'а

spring:
  rabbitmq:
    host: rmq.example.com
    virtual-host: MYAPP
    username: user
    password: pass
```

### 3. Отправка команды

```java
@Service
public class PaymentService {
    private final CommandBus commandBus;

    public PaymentService(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public PaymentResult pay(BigDecimal amount) {
        return commandBus.execute(new CreatePaymentCommand(amount));
    }
}
```

### 4. Обработка команды

```java
@Component
public class PaymentHandlers {

    @CommandHandler
    public CreatePaymentResult createPayment(CreatePaymentCommand cmd) {
        // бизнес-логика
        return new CreatePaymentResult("pay-123", "COMPLETED");
    }
}
```

Starter сам объявит exchange `CommandExchange`, очередь `Command_TCB.Payment.Commands.CreatePaymentCommand`, биндинг и подписку — достаточно `@CommandHandler` на методе бина. Регистрация типов в `WireTypeRegistry` тоже автоматическая (по `@WireName` на командах/результатах).

### 5. Объявление команд (shared-модуль)

```java
@WireName(value = "TCB.Payment.Commands.CreatePaymentCommand",
          assembly = "TCB.Payment.Client")
public class CreatePaymentCommand implements CommandWithResult<CreatePaymentResult> {
    public BigDecimal amount;
}

@WireName(value = "TCB.Payment.Results.CreatePaymentResult",
          assembly = "TCB.Payment.Client")
public class CreatePaymentResult implements CommandResult {
    public String paymentId;
    public String status;
}
```

### 6. Тесты без RabbitMQ

```java
@SalCommandsTest
class PaymentHandlersTest {
    @Autowired CommandBus commandBus;

    @Test
    void createPayment_works() {
        var result = commandBus.execute(new CreatePaymentCommand(BigDecimal.TEN));
        assertThat(result.status()).isEqualTo("COMPLETED");
    }
}
```

## Модули

| Модуль | Назначение | Зависимости |
|---|---|---|
| `sal-commands-api` | Публичные контракты: маркеры, аннотации, CommandBus, SalSession, exceptions | jackson-annotations |
| `sal-commands-core` | Реализация: CommandBusImpl, transport, handler registry, wire DTOs, converter | api + jackson + spring-context |
| `sal-commands-spring-boot-starter` | AutoConfiguration + Properties | core + spring-boot-starter-amqp |
| `sal-commands-test` | `@SalCommandsTest` + InMemoryCommandTransport | core + spring-boot-test |
| `sal-commands-demo-app` | Web UI для E2E тестирования с .NET | starter + spring-boot-starter-web |
| `sal-commands-example` | Минимальный пример использования starter'а | starter + spring-boot-starter-web |
| `sal-commands-wire-probe` | CLI утилита для захвата RMQ трафика | amqp-client + jackson |

## Сборка

```bash
# локально — Maven Central напрямую
mvn clean install -DskipTests

# внутри корпоративного контура — через Nexus
mvn -Pnexus clean install -DskipTests
```

Профиль `nexus` подключает внутренний прокси Maven Central (`http://localhost:8081/repository/maven-public/` — переопределяется в `~/.m2/settings.xml`).

## Документация

- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — архитектура, компоненты, потоки данных
- [WIRE_FORMAT.md](docs/WIRE_FORMAT.md) — wire-протокол .NET SAL (AMQP properties, headers, body)
- [SAL_PROTOCOL_SPEC.md](../docs/SAL_PROTOCOL_SPEC.md) — полная спецификация протокола для реализации на любом языке
- [MIGRATION_GUIDE.md](docs/MIGRATION_GUIDE.md) — как мигрировать .NET-команду на Java
- [DEVELOPMENT.md](docs/DEVELOPMENT.md) — разработка, сборка, тесты, wire-probe

## Статистика

- 50 source файлов, 3374 LOC
- 16 test файлов, 1167 LOC
- 40 unit-тестов
- 7 модулей
- Java 21 + Spring Boot 3.4.5
