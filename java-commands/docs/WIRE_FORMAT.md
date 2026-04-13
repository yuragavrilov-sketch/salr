# SAL Commands — Wire Format Specification

> Этот документ описывает ТОЧНЫЙ wire-формат обмена между Java и .NET адаптерами через RabbitMQ. Основан на реальных дампах из production .NET SAL (тестовый стенд PAYTEST, апрель 2026). Любые изменения должны быть верифицированы на реальном стенде.

## Общая модель

.NET SAL **НЕ** сериализует `RecordedMessage` как единый JSON. Метаданные распределены по AMQP-слоям:

```
AMQP Message:
├── body (bytes)       ← JSON payload (команда или event)
├── properties         ← correlationId, messageId, priority, timestamp, content_type
├── headers            ← Additional-Data (вложенный JSON), Accept-Language
└── envelope           ← exchange, routingKey (параметры publish)
```

## 1. Команда (A → CommandExchange → B)

### AMQP Properties

| Property | Значение | Пример |
|---|---|---|
| `content_type` | AssemblyQualifiedName команды | `TCB.MercLibrary.Client.Commands.GetConfigurationCommand, TCB.MercLibrary.Client` |
| `correlation_id` | UUID строка | `bf098704-37f2-4122-b8ea-cf7392cd1624` |
| `message_id` | Числовая строка (счётчик) | `"171377205"` |
| `priority` | int (0-10) | `5` |
| `delivery_mode` | 2 (persistent) | `2` |
| `timestamp` | epoch seconds (Date) | `1776076286` |

### AMQP Headers

| Header | Тип | Значение |
|---|---|---|
| `Additional-Data` | String | **Вложенная JSON-строка**: `{"Session":"<base64>","IsCommand":"","SourceServiceId":"<name>"}` |
| `Accept-Language` | String | `"ru-RU"` |

### Additional-Data (разобранный)

| Ключ | Значение | Назначение |
|---|---|---|
| `Session` | Base64-строка | Сериализованная сессия (см. раздел Session) |
| `IsCommand` | Пустая строка `""` | Маркер: это команда (не событие) |
| `SourceServiceId` | Имя адаптера-отправителя | Используется как routing key при отправке ответа |
| `NoCreateQueue` | Пустая строка (опционально) | Флаг — не создавать очередь |

### AMQP Envelope

| Поле | Значение |
|---|---|
| `exchange` | `"CommandExchange"` |
| `routingKey` | .NET FullName команды (без assembly): `"TCB.MercLibrary.Client.Commands.GetConfigurationCommand"` |

### Body

Чистый JSON команды — поля как в .NET-классе (naming не форсируется):

```json
{"MercID":"103337","GateId":"OCT","Is3DS":false,"Mps":"VISA"}
```

## 2. CommandCompletedEvent (B → A)

### AMQP Properties

| Property | Значение |
|---|---|
| `content_type` | `"TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure"` |
| `correlation_id` | Тот же UUID, что в оригинальной команде |
| `message_id` | Числовая строка (свой счётчик handler-адаптера) |
| `priority` | `5` |
| `delivery_mode` | `2` |
| `timestamp` | epoch seconds |

### AMQP Headers

| Header | Значение |
|---|---|
| `Additional-Data` | `{"Session":"<base64>","SourceServiceId":"<handler-adapter-name>"}` |
| `Accept-Language` | `"ru-RU"` |

### AMQP Envelope

| Поле | Значение |
|---|---|
| `exchange` | `"TCB.Infrastructure.Command.CommandCompletedEvent"` |
| `routingKey` | `SourceServiceId` из ОРИГИНАЛЬНОЙ команды (обратный адрес) |

### Body

JSON с четырьмя полями верхнего уровня:

```json
{
  "ResultType": "TCB.MercLibrary.Client.Results.GetConfigurationCommandResult, TCB.MercLibrary.Client",
  "Result": { /* typed result object */ },
  "AdditionalData": {},
  "Context": {
    "CommandType": "TCB.MercLibrary.Client.Commands.GetConfigurationCommand",
    "CorrelationId": "060afc81-1b8e-478a-bdfe-d5e949d395c0",
    "SourceServiceId": "WorkFlowMashineMain",
    "TimeStamp": "2026-04-13T11:47:54",
    "Priority": "Normal",
    "ExcutionServiceId": "TCB.MercLibrary",
    "ExcutionTimeStamp": "2026-04-13T11:47:54.50428+03:00",
    "ExcutionDuration": "00:00:00.2392809",
    "SessionId": "23601100#1484#23",
    "OperationId": "171478030"
  }
}
```

## 3. CommandFailedEvent (B → A)

Структура аналогична CommandCompletedEvent, отличается body.

### AMQP Properties

| Property | Значение |
|---|---|
| `content_type` | `"TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure"` |
| (остальные) | Аналогично CompletedEvent |

### AMQP Envelope

| Поле | Значение |
|---|---|
| `exchange` | `"TCB.Infrastructure.Command.CommandFailedEvent"` |
| `routingKey` | `SourceServiceId` из оригинальной команды |

### Body

```json
{
  "ExceptionData": {
    "ExceptionType": "Fatal",
    "Code": "FatalException",
    "Message": "Sequence contains no matching element",
    "AdapterName": "TCB.MercLibrary",
    "SourceType": "CommandHandler",
    "SourcePath": "TCB.MercLibrary.Client.Commands.GetFeeCommand",
    "SessionId": "[SID:23601100#1484#23:00171478030]",
    "SourceId": "d57ff9b6-5372-4ec0-ae1a-2428a97152aa",
    "TimeStamp": "2026-04-13T11:47:55.0428798+03:00",
    "Properties": {"Type": "System.InvalidOperationException"},
    "InnerException": null
  },
  "AdditionalData": {},
  "Context": { /* same as CompletedEvent */ }
}
```

## 4. Context — формат полей

| Поле | Тип | Формат | Пример |
|---|---|---|---|
| `CommandType` | String | .NET FullName | `"TCB.MercLibrary.Client.Commands.GetConfigurationCommand"` |
| `CorrelationId` | String | UUID | `"060afc81-..."` |
| `SourceServiceId` | String | Имя адаптера-отправителя | `"WorkFlowMashineMain"` |
| `TimeStamp` | String | **Без TZ, без Z** | `"2026-04-13T11:47:54"` |
| `Priority` | String | **Enum name, не число** | `"Normal"` |
| `ExcutionServiceId` | String | Имя handler-адаптера (**опечатка!**) | `"TCB.MercLibrary"` |
| `ExcutionTimeStamp` | String | **С TZ offset** | `"2026-04-13T11:47:54.50428+03:00"` |
| `ExcutionDuration` | String | **.NET TimeSpan, не ISO PT** | `"00:00:00.2392809"` |
| `SessionId` | String | ID сессии | `"23601100#1484#23"` |
| `OperationId` | String | ID операции | `"171478030"` |

**КРИТИЧНО**: опечатки `Excution*` (без буквы 'e') — часть wire-протокола. Менять НЕЛЬЗЯ.

## 5. Session — формат сериализации

```
ObjectNode session → JSON string (Newtonsoft/Jackson)
  → BinaryWriter.Write(string):
      Write7BitEncodedInt(utf8.length)  // variable-length prefix
      Write(utf8_bytes)                 // raw UTF-8
  → DeflateStream (raw DEFLATE, БЕЗ zlib header)
  → Convert.ToBase64String
```

### Java-реализация

```java
// Serialize
byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);  // true = nowrap!
try (DeflaterOutputStream deflate = new DeflaterOutputStream(bos, deflater)) {
    write7BitEncodedInt(deflate, utf8.length);
    deflate.write(utf8);
}
Base64.getEncoder().encodeToString(bos.toByteArray());

// Deserialize
Inflater inflater = new Inflater(true);  // true = nowrap!
try (InflaterInputStream inflate = new InflaterInputStream(bis, inflater)) {
    int length = read7BitEncodedInt(inflate);
    byte[] utf8 = inflate.readNBytes(length);
    mapper.readTree(new String(utf8, StandardCharsets.UTF_8));
}
```

### 7-bit Encoded Int (формат .NET BinaryWriter)

Каждый байт хранит 7 бит данных; старший бит = "есть ещё байты":

```
Значение 0-127:     1 байт:  0xxxxxxx
Значение 128-16383: 2 байта: 1xxxxxxx 0xxxxxxx
```

### КРИТИЧНО: nowrap=true

Java `DeflaterOutputStream` по умолчанию оборачивает данные в **zlib контейнер** (2 байта header `0x78 0x9C` + 4 байта checksum). .NET `DeflateStream` = **raw DEFLATE** без обёртки. Без `nowrap=true` .NET крашится:

```
System.IO.Compression.Inflater.DecodeUncompressedBlock:
Block length does not match with its complement.
```

## 6. Очереди — naming conventions

| Тип очереди | Формат имени | Пример |
|---|---|---|
| Результаты адаптера | `cmd.<adapterName>` | `cmd.JavaDemoAdapter` |
| Handler команды | `Command_<commandFullName>` | `Command_TCB.MercLibrary.Client.Commands.GetConfigurationCommand` |

**.NET SAL проверяет наличие очереди** `Command_<type>` при отправке команды. Если очереди нет — ошибка "Очередь обработчика команд не найдена".

## 7. Routing keys

| Направление | Routing key | Источник |
|---|---|---|
| Команда A→B | `FullName` команды (.NET type) | `contentType` без assembly-части |
| Результат B→A | `SourceServiceId` из оригинальной команды | `Additional-Data.SourceServiceId` |

## 8. Exchanges

Все три — `direct` (не topic, не fanout):

```
CommandExchange                                    — direct, durable
TCB.Infrastructure.Command.CommandCompletedEvent   — direct, durable
TCB.Infrastructure.Command.CommandFailedEvent      — direct, durable
```

Wildcard (`#`, `*`) **не работают** на direct exchanges. Для захвата всего трафика — clone bindings через Management API (см. sal-commands-wire-probe).
