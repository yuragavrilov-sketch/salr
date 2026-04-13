# Разработка SAL Commands

## Требования

- Java 21 (OpenJDK/Temurin)
- Maven 3.9+
- RabbitMQ (для E2E тестов) — тестовый стенд или локальный Docker
- Nexus на localhost:8081 (опционально, конфигурирован в parent pom)

## Сборка

```bash
cd java-commands

# Полная сборка с тестами
mvn clean verify

# Без тестов (быстрая)
mvn clean install -DskipTests

# Отдельный модуль
mvn -pl sal-commands-core -am test
```

## Структура тестов

| Тест-класс | Модуль | Что проверяет |
|---|---|---|
| `CommandPriorityTest` | api | byte values .NET enum |
| `WireNameTest` | api | аннотация @WireName |
| `CommandContextTest` | api | .NET TimeSpan parsing |
| `CommandHandlerTest` | api | аннотация @CommandHandler |
| `SalSessionTest` | api | мутабельная сессия |
| `WireTypeRegistryTest` | core | реестр Class↔wireName, fail-fast |
| `SessionSerializerTest` | core | BinaryWriter+Deflate round-trip + реальная .NET сессия |
| `RealWireFormatTest` | core | парсинг реальных .NET дампов |
| `ResponseBuilderTest` | core | .NET-формат Context, TimeSpan, events |
| `CorrelationStoreTest` | core | register, remove, timeout, thread-safety |
| `CommandBusImplTest` | core | publish с mock transport, timeout, failure |
| `CommandHandlerRegistryTest` | core | register, duplicate, find |
| `ExceptionMapperTest` | core | business/technical mapping |
| `InMemoryCommandTransportTest` | core | **full round-trip** без RMQ |

**Самый важный тест**: `InMemoryCommandTransportTest.fullRoundTrip_executeReturnsTypedResult` — доказывает всю цепочку `CommandBus.execute() → handler → result`.

## Golden файлы

`sal-commands-core/src/test/resources/golden/` — реальные дампы из production .NET SAL, захваченные через wire-probe:

| Файл | Тип | Откуда |
|---|---|---|
| `msg-001-*.json` | Command | CommandExchange |
| `msg-002-*.json` | CommandCompletedEvent | Completed exchange |
| `msg-003-*.json` | CommandFailedEvent | Failed exchange |

**НЕ модифицировать эти файлы** без замены реальными дампами с .NET стенда. Если тест падает по `assertJsonTreesEqual` — проблема в конвертере, не в golden.

## Wire-probe утилита

CLI для захвата RMQ трафика:

```bash
cd sal-commands-wire-probe

# Справка
mvn compile exec:java "-Dexec.args=--help"

# Захват всего трафика vhost'а
mvn compile exec:java "-Dexec.args=--host=rmq --user=u --pass=p --vhost=MYAPP --clone-all"

# Захват трафика конкретного адаптера
mvn compile exec:java "-Dexec.args=--host=rmq --user=u --pass=p --vhost=MYAPP --clone-queues=adapter-queue-name"

# Конкретные exchange+routing key
mvn compile exec:java "-Dexec.args=--host=rmq --user=u --pass=p --vhost=MYAPP --bindings=CommandExchange:TCB.Some.Command"
```

Параметры:

| Параметр | Default | Описание |
|---|---|---|
| `--host` | required | RMQ host |
| `--port` | 5672 | AMQP port |
| `--vhost` | / | virtual host |
| `--user` | required | username |
| `--pass` | required | password |
| `--bindings` | - | exchange:routingKey pairs |
| `--clone-queues` | - | clone bindings from queues |
| `--clone-all` | false | clone from ALL queues |
| `--per-key-limit` | 3 | max samples per routing key |
| `--idle-timeout` | 30 | exit after N seconds of silence |
| `--output-dir` | ./probe-output | where to save dumps |
| `--mgmt-port` | 15672 | Management API port |

## Demo-app

Web UI для интерактивного E2E тестирования:

```bash
mvn -pl sal-commands-demo-app -am package -DskipTests

java -jar sal-commands-demo-app/target/sal-commands-demo-app-1.0.0-SNAPSHOT.jar \
  --spring.rabbitmq.host=rmq \
  --spring.rabbitmq.virtual-host=MYAPP \
  --spring.rabbitmq.username=user \
  --spring.rabbitmq.password=pass \
  --demo.adapter-name=JavaDemoAdapter \
  --demo.handle-commands=TCB.JavaDemo.Commands.EchoCommand \
  --server.port=9090
```

Открыть `http://localhost:9090` — форма отправки команд + live-polling результатов.

## Example-app

Минимальный пример использования starter'а:

```bash
mvn -pl sal-commands-example -am package -DskipTests

java -jar sal-commands-example/target/sal-commands-example-1.0.0-SNAPSHOT.jar \
  --spring.rabbitmq.host=rmq \
  --spring.rabbitmq.virtual-host=MYAPP \
  --spring.rabbitmq.username=user \
  --spring.rabbitmq.password=pass \
  --sal.command-bus.adapter-name=JavaExampleAdapter
```

REST: `POST /api/echo {"message":"hello"}` → `{"echo":"ECHO: hello","processedBy":"sal-commands-example"}`

## Git workflow

- `main` — стабильная ветка
- `feature/*` — фичи, merge через `--no-ff`
- Commit messages: `feat:`, `fix:`, `refactor:`, `test:`, `chore:`, `docs:`
- Коммит на каждый завершённый набор изменений

## Стандарты кода

- Java 21: records, sealed interfaces, pattern matching
- **Без Lombok** — explicit builders/records
- **Без `@Autowired` на полях** — только constructor injection
- Unit-тесты: JUnit 5 + Mockito + AssertJ
- Логирование: SLF4J с тегами `[BUS]`, `[CMD]`, `[RESULT]`, `[HANDLER]`, `[TRANSPORT]`, `[TIMEOUT]`
- Все таймауты/лимиты — в `application.yml`, не хардкодить

## Известные ограничения v1.0

| Ограничение | Обход |
|---|---|
| Нет outbox (at-most-once для команд) | Бизнес-код ретраит при сбое |
| Нет RetryPolicy на уровне фреймворка | Spring Retry на бизнес-уровне |
| Нет observer result handlers | Spring ApplicationEventPublisher |
| Нет hot-регистрации handler'ов | Рестарт приложения |
| SessionSerializer: TZ offset захардкожен +03:00 | TODO: брать из системного timezone |
| WireTypeRegistry: нет classpath scan | Ручная регистрация через @Bean |
| Confirmation flow: только приём, не отправка | Добавить в v1.1 |
