# Миграция .NET-команды на Java

Пошаговое руководство: как перенести существующую .NET-команду с handler'ом на Java, сохраняя wire-совместимость.

## Пререквизиты

- Java-адаптер подключён к `sal-commands-spring-boot-starter`
- Доступ к .NET source-коду мигрируемой команды (DTO + handler)
- Доступ к тестовому RMQ vhost

## Шаг 1: Определить wire-имена

Из .NET source-кода (или из RMQ Management UI → exchanges → bindings):

```csharp
// .NET command
namespace TCB.MercLibrary.Client.Commands
{
    public class GetConfigurationCommand : IHaveResult<GetConfigurationCommandResult>
    {
        public string MercID { get; set; }
        public string GateId { get; set; }
        public bool Is3DS { get; set; }
        public string Mps { get; set; }
    }
}
```

Из этого извлекаем:
- **FullName**: `TCB.MercLibrary.Client.Commands.GetConfigurationCommand`
- **Assembly**: `TCB.MercLibrary.Client`
- **Поля**: `MercID` (string), `GateId` (string), `Is3DS` (bool), `Mps` (string)

Для результата — аналогично:
- **FullName**: `TCB.MercLibrary.Client.Results.GetConfigurationCommandResult`
- **Assembly**: `TCB.MercLibrary.Client`

## Шаг 2: Создать Java DTO (shared-модуль)

```java
@WireName(value = "TCB.MercLibrary.Client.Commands.GetConfigurationCommand",
          assembly = "TCB.MercLibrary.Client")
public class GetConfigurationCommand implements CommandWithResult<GetConfigurationCommandResult> {

    // Имена полей в JSON должны совпадать с .NET — используем @JsonProperty
    @JsonProperty("MercID")    public String mercId;
    @JsonProperty("GateId")    public String gateId;
    @JsonProperty("Is3DS")     public boolean is3ds;
    @JsonProperty("Mps")       public String mps;

    public GetConfigurationCommand() {}
}

@WireName(value = "TCB.MercLibrary.Client.Results.GetConfigurationCommandResult",
          assembly = "TCB.MercLibrary.Client")
public class GetConfigurationCommandResult implements CommandResult {

    @JsonProperty("Name")           public String name;
    @JsonProperty("EnglishName")    public String englishName;
    @JsonProperty("Currency")       public String currency;
    // ... остальные поля
}
```

**ВАЖНО**: `@JsonProperty` нужен если Java-имя поля отличается от .NET JSON-имени. Если совпадают (например `public String Name;`) — не нужен.

## Шаг 3a: Отправка команды (Java → .NET)

```java
@Service
public class MercService {

    private final CommandBus commandBus;

    public MercService(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public GetConfigurationCommandResult getConfig(String mercId) {
        var cmd = new GetConfigurationCommand();
        cmd.mercId = mercId;
        cmd.gateId = "OCT";
        cmd.is3ds = false;
        cmd.mps = "VISA";

        return commandBus.execute(cmd);  // блокирующий вызов, таймаут из конфига
    }
}
```

Никакой дополнительной конфигурации — starter автоматически:
1. Резолвит wire-name из `@WireName`
2. Сериализует команду в JSON
3. Публикует в `CommandExchange` с правильными AMQP properties
4. Ждёт ответ через `CorrelationStore`
5. Десериализует `CommandCompletedEvent.Result` в `GetConfigurationCommandResult`

## Шаг 3b: Обработка команды (Java handler)

```java
@Component
public class MercHandlers {

    private final MercConfigRepository repo;

    public MercHandlers(MercConfigRepository repo) {
        this.repo = repo;
    }

    @CommandHandler
    public GetConfigurationCommandResult getConfig(GetConfigurationCommand cmd) {
        var config = repo.findByMercId(cmd.mercId);
        var result = new GetConfigurationCommandResult();
        result.name = config.name();
        result.currency = config.currency();
        // ...
        return result;
    }
}
```

Starter автоматически:
1. Найдёт `@CommandHandler` при старте
2. Создаст очередь `Command_TCB.MercLibrary.Client.Commands.GetConfigurationCommand`
3. Забиндит к `CommandExchange`
4. При приходе команды — десериализует, вызовет handler, сериализует результат
5. Отправит `CommandCompletedEvent` обратно

## Шаг 4: Зарегистрировать типы в WireTypeRegistry

Если используешь только `@WireName` — starter найдёт их через classpath scan (TODO: пока ручная регистрация). В текущей версии нужно зарегистрировать типы при старте:

```java
@Configuration
public class CommandConfig {

    @Bean
    public WireTypeRegistrar mercTypes(WireTypeRegistry registry) {
        registry.register(GetConfigurationCommand.class);
        registry.register(GetConfigurationCommandResult.class);
        return () -> {};  // marker bean
    }
}
```

## Шаг 5: Тестирование

### Unit-тест (без RabbitMQ)

```java
@SalCommandsTest
class MercHandlersTest {

    @Autowired CommandBus commandBus;
    @MockBean MercConfigRepository repo;

    @Test
    void getConfig_returnsName() {
        when(repo.findByMercId("123")).thenReturn(new MercConfig("Наймикс", "643"));

        var result = commandBus.execute(
            new GetConfigurationCommand("123", "OCT", false, "VISA"));

        assertThat(result.name).isEqualTo("Наймикс");
    }
}
```

### E2E тест (с реальным RMQ и .NET)

1. Запусти demo-app или example-app с `--demo.handle-commands=TCB.MercLibrary...`
2. Из .NET CommandTester отправь команду
3. Проверь в логах Java: `[CMD] Received` → `[CMD] Sent completed`
4. Проверь в логах .NET: ответ получен

### Wire-probe (захват трафика)

```bash
java -jar sal-commands-wire-probe.jar \
  --host=rmq --user=u --pass=p --vhost=MYAPP \
  --clone-all --idle-timeout=30
```

## Чеклист миграции

- [ ] Определены wire-имена (FullName + Assembly) для команды и результата
- [ ] Созданы Java DTO с `@WireName` и `@JsonProperty` (если нужен)
- [ ] Зарегистрированы в `WireTypeRegistry`
- [ ] Handler помечен `@CommandHandler` в `@Component`
- [ ] Unit-тест через `@SalCommandsTest` зелёный
- [ ] E2E тест на реальном стенде пройден (оба направления)
- [ ] Старый .NET handler отключён (если полная замена)

## Типичные ошибки

| Симптом | Причина | Решение |
|---|---|---|
| `Block length does not match` | Session serializer без `nowrap=true` | Проверить Deflater(DEFAULT_COMPRESSION, **true**) |
| `NullReferenceException` в .NET handler | Session не отправлена или в неверном формате | Проверить BinaryWriter формат в SessionSerializer |
| `.NET: Очередь обработчика команд не найдена` | Нет очереди `Command_<FullName>` | Проверить, что Java-адаптер запущен и binding создан |
| Результат не приходит (таймаут) | SourceServiceId не совпадает с binding на result-очереди | Проверить `Additional-Data.SourceServiceId` = `sal.command-bus.adapter-name` |
| Поля результата null | JSON-имена не совпадают с .NET | Добавить `@JsonProperty("DotNetFieldName")` |
| `Duplicate @CommandHandler` при старте | Два handler'а для одного wire-name | Убрать дубликат |
