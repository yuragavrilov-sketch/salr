# SAL Command Bus — Полная спецификация протокола

> Версия: 1.0 (2026-04-14)
> Источники: дампы реального .NET production-стенда PAYTEST (RMQ 10.99.147.235),
> исходники TCB.SAL.Common, верифицированная Java-имплементация (`java-commands/`).
> Цель: документ позволяет реализовать SAL-совместимый клиент/сервер на любом языке.

---

## Часть I. Обзор

### 1.1. Назначение

SAL (Service Adapter Layer) — внутренний протокол асинхронного RPC поверх RabbitMQ для
обмена командами между сервисами-«адаптерами». Изначально реализован на .NET
(`TCB.SAL.Common`), используется в продуктовом ландшафте TCB. Каждая команда — это
типизированный запрос с гарантированным ответом (успех или ошибка), с поддержкой:

- произвольных типов команд и результатов;
- дозированного контекста выполнения (`CommandContext`);
- shared-сессии (`Session`), кочующей между адаптерами вместе с командой;
- структурированных бизнес-/технических ошибок (`InfrastructureExceptionDto`);
- приоритезации, корреляции, локализации.

### 1.2. Транспортная модель

Используется **исключительно RabbitMQ AMQP 0-9-1**. Никакого HTTP/gRPC. Все три
обменника — `direct`, `durable`, без auto-delete:

| Exchange | Назначение | Тип |
|---|---|---|
| `CommandExchange` | команды (запросы) от клиента к обработчику | direct, durable |
| `TCB.Infrastructure.Command.CommandCompletedEvent` | успешные результаты | direct, durable |
| `TCB.Infrastructure.Command.CommandFailedEvent` | ошибки | direct, durable |

Один логический «обмен сообщениями» = один request (publish в `CommandExchange`) + один
response (publish в один из событийных exchange-ей). Корреляция строго через
`correlation_id`.

### 1.3. Адаптер

**Адаптер** — это запущенный процесс/сервис, который:

- имеет уникальное в рамках vhost имя `adapterName` (строка, например `WorkFlowMashineMain`);
- слушает свою «адресную» очередь результатов `cmd.<adapterName>` (см. §6);
- может объявлять любое число handler-очередей `Command_<FullName>` (см. §6) для приёма команд;
- посылает команды другим адаптерам через `CommandExchange`;
- получает ответы строго в свою `cmd.<adapterName>`-очередь.

Адаптер одновременно может быть и клиентом, и сервером (асимметрии нет).

### 1.4. Жизненный цикл одной команды

```
[Adapter A: caller]                                   [Adapter B: handler]
 1. сериализует Command в JSON
 2. строит AMQP-сообщение (см. §3)
 3. publish → CommandExchange
       routingKey = FullName(Command)
 4. ждёт ответ в очереди cmd.A
                                                       5. получает из Command_<FullName>
                                                       6. десериализует body как Command
                                                       7. читает Session из header
                                                       8. вызывает handler
                                                       9a. УСПЕХ → publish в
                                                            CommandCompletedEvent
                                                            routingKey = SourceServiceId(=A)
                                                       9b. ОШИБКА → publish в
                                                            CommandFailedEvent
                                                            routingKey = SourceServiceId(=A)
10. матчит ответ по correlation_id
11. десериализует Result или ExceptionData
12. резолвит ожидающий future
```

---

## Часть II. AMQP-уровень

### 2.1. Топология

Объявления (idempotent, безопасно повторять):

```
exchange.declare CommandExchange                                    type=direct durable=true
exchange.declare TCB.Infrastructure.Command.CommandCompletedEvent   type=direct durable=true
exchange.declare TCB.Infrastructure.Command.CommandFailedEvent      type=direct durable=true
```

#### Очередь результатов адаптера

Каждый адаптер при старте объявляет ОДНУ очередь:

```
queue.declare cmd.<adapterName>   durable=true  exclusive=false  auto-delete=false
queue.bind    cmd.<adapterName>   exchange=TCB.Infrastructure.Command.CommandCompletedEvent  routingKey=<adapterName>
queue.bind    cmd.<adapterName>   exchange=TCB.Infrastructure.Command.CommandFailedEvent     routingKey=<adapterName>
```

В эту очередь приходят и Completed, и Failed для всех команд, **отправленных этим адаптером**.

#### Handler-очередь команды

Для каждого типа команды, который адаптер готов обрабатывать, объявляется отдельная очередь:

```
queue.declare Command_<FullName>  durable=true  exclusive=false  auto-delete=false
queue.bind    Command_<FullName>  exchange=CommandExchange  routingKey=<FullName>
```

где `<FullName>` — .NET FullName (см. §5.1).

**КРИТИЧНО:** при отправке команды .NET-сторона проверяет существование очереди
`Command_<FullName>` через RMQ Management API. Если очереди нет — команда не отправляется
и возвращается ошибка `«Очередь обработчика команд не найдена»`.

### 2.2. AMQP свойства, общие для всех сообщений SAL

| Свойство | Значение | Обязательность |
|---|---|---|
| `delivery_mode` | `2` (persistent) | обязательно |
| `content_type` | AssemblyQualifiedName типа сообщения (см. §5.1) | обязательно |
| `priority` | байт `0..10`, как правило `5` (Normal) | обязательно |
| `correlation_id` | UUID-строка в нижнем регистре | обязательно |
| `message_id` | строка с **числовым счётчиком отправителя** (НЕ UUID) | обязательно |
| `timestamp` | epoch seconds (AMQP timestamp) | обязательно |
| `headers` | см. §2.3 | обязательно |

`reply_to`, `expiration`, `type`, `user_id`, `app_id` НЕ используются.

### 2.3. AMQP заголовки

Два заголовка, оба строковые:

| Header | Тип | Назначение |
|---|---|---|
| `Accept-Language` | string | Локаль вызова, например `"ru-RU"` |
| `Additional-Data` | string | **Вложенный JSON-объект, СЕРИАЛИЗОВАННЫЙ В СТРОКУ** |

`Additional-Data` — это не AMQP table, не nested map, а именно строка с JSON. Её надо
явно `JSON.stringify` при отправке и `JSON.parse` при чтении. Структура содержимого
зависит от направления (см. §3.1.3 и §4.1.3).

### 2.4. Приоритеты

Поле `priority` — байт `0..10`. Соответствие .NET-enum `CommandPriority`:

| Имя (для сериализации в Context) | Числовое значение |
|---|---|
| `Idle` | 0 |
| `BelowNormal` | 4 |
| `Normal` | 5 |
| `AboveNormal` | 6 |
| `High` | 9 |
| `RealTime` | 10 |

В AMQP property `priority` используется ЧИСЛО.
В JSON-поле `Context.Priority` (§4.4) используется ИМЯ как строка.

---

## Часть III. Сообщение «Команда» (запрос)

### 3.1. Структура

#### 3.1.1. Envelope

| Поле | Значение |
|---|---|
| `exchange` | `"CommandExchange"` |
| `routing_key` | FullName команды (без assembly): `TCB.MercLibrary.Client.Commands.GetConfigurationCommand` |

#### 3.1.2. Properties

| Property | Значение |
|---|---|
| `content_type` | AssemblyQualifiedName: `"TCB.MercLibrary.Client.Commands.GetConfigurationCommand, TCB.MercLibrary.Client"` |
| `correlation_id` | UUID v4: `"bf098704-37f2-4122-b8ea-cf7392cd1624"` |
| `message_id` | строка-число: `"171377205"` |
| `priority` | `5` |
| `delivery_mode` | `2` |
| `timestamp` | `1776076286` |

#### 3.1.3. Headers

```
Accept-Language: "ru-RU"
Additional-Data: "{\"Session\":\"<base64>\",\"IsCommand\":\"\",\"SourceServiceId\":\"<adapterName>\"}"
```

Содержимое распарсенного `Additional-Data` для команды:

| Ключ | Тип | Назначение |
|---|---|---|
| `Session` | string (Base64) | сериализованная сессия (§7) |
| `IsCommand` | string (всегда `""`) | дискриминатор: «это команда, не событие» |
| `SourceServiceId` | string | имя адаптера-отправителя — становится routing_key для ответа |
| `NoCreateQueue` | string `""` | (опционально) флаг «не создавать handler-очередь автоматом» |

Порядок ключей не значим. Значения всегда строковые (даже флаги — пустая строка).

#### 3.1.4. Body

Тело сообщения = **чистый JSON команды**. Имена полей — как в .NET-классе (PascalCase
по умолчанию, но никакая naming-стратегия не форсируется — что объявил .NET-класс, то
и идёт в проводе). Никакой обёртки, без поля `type`, без `$type`.

Пример:

```json
{"MercID":"103337"}
```

Кодировка — UTF-8 без BOM.

### 3.2. Полный пример (реальный дамп)

```
exchange    : CommandExchange
routingKey  : TCB.MercLibrary.Client.Commands.GetConfigurationCommand

properties:
  content_type     : "TCB.MercLibrary.Client.Commands.GetConfigurationCommand, TCB.MercLibrary.Client"
  delivery_mode    : 2
  priority         : 5
  correlation_id   : "060afc81-1b8e-478a-bdfe-d5e949d395c0"
  message_id       : "171478030"
  timestamp        : 1776080874     # 2026-04-13T11:47:54Z

headers:
  Accept-Language  : "ru-RU"
  Additional-Data  : "{\"Session\":\"bZTbsqpGEIYfIG/B...AA==\",\"IsCommand\":\"\",\"SourceServiceId\":\"WorkFlowMashineMain\"}"

body (UTF-8):
  {"MercID":"103337"}
```

---

## Часть IV. Сообщения-ответы

Ответ публикуется в один из двух обменников в зависимости от исхода. Routing key —
**значение `SourceServiceId` из исходной команды** (т.е. имя адаптера-вызывателя).

### 4.1. CommandCompletedEvent (успех)

#### 4.1.1. Envelope

| Поле | Значение |
|---|---|
| `exchange` | `"TCB.Infrastructure.Command.CommandCompletedEvent"` |
| `routing_key` | значение `SourceServiceId` из исходной команды |

#### 4.1.2. Properties

| Property | Значение |
|---|---|
| `content_type` | `"TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure"` |
| `correlation_id` | **тот же** UUID, что в исходной команде |
| `message_id` | строка-число, собственный счётчик handler-адаптера |
| `priority` | как правило `5` (можно копировать из команды) |
| `delivery_mode` | `2` |
| `timestamp` | epoch seconds на момент публикации |

#### 4.1.3. Headers

```
Accept-Language: "ru-RU"
Additional-Data: "{\"Session\":\"<base64>\",\"SourceServiceId\":\"<handler-adapter-name>\"}"
```

В ответе **нет** `IsCommand`. `SourceServiceId` теперь — имя handler-адаптера (того, кто
отвечает). `Session` — обновлённая сессия (если handler её менял; иначе можно вернуть
исходную).

#### 4.1.4. Body

```json
{
  "ResultType": "TCB.MercLibrary.Client.Results.GetConfigurationCommandResult, TCB.MercLibrary.Client",
  "Result": { /* типизированный объект результата */ },
  "AdditionalData": {},
  "Context": { /* см. §4.4 */ }
}
```

Поля верхнего уровня — строго в PascalCase, строго эти четыре, в любом порядке:

| Поле | Тип | Описание |
|---|---|---|
| `ResultType` | string | AssemblyQualifiedName типа результата |
| `Result` | object | сам результат, JSON-сериализованный объект |
| `AdditionalData` | object (map<string,string>) | произвольные строковые KV; если нечего — `{}` |
| `Context` | object | контекст выполнения (§4.4) |

### 4.2. CommandFailedEvent (ошибка)

Envelope/Properties/Headers — идентичны §4.1 за исключением:

| Property | Значение |
|---|---|
| `content_type` | `"TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure"` |
| `exchange` | `"TCB.Infrastructure.Command.CommandFailedEvent"` |

#### 4.2.1. Body

```json
{
  "ExceptionData": { /* см. §4.3 */ },
  "AdditionalData": {},
  "Context": { /* см. §4.4 */ }
}
```

### 4.3. ExceptionData

Структура `InfrastructureExceptionDto` — рекурсивная (через `InnerException`):

```json
{
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
}
```

| Поле | Тип | Описание |
|---|---|---|
| `ExceptionType` | string | категория: `"Fatal"`, `"Business"`, `"Technical"` или произвольная |
| `Code` | string | код ошибки. **Непустой** ⇒ бизнес-ошибка; **пустой/null** ⇒ техническая |
| `Message` | string | человекочитаемое сообщение |
| `AdapterName` | string | имя адаптера, где возникла ошибка |
| `SourceType` | string | категория источника (`CommandHandler`, `Pipeline`, ...) |
| `SourcePath` | string | детали источника (обычно FullName команды) |
| `SessionId` | string | ID сессии (формат произвольный, обычно `"[SID:...]"`) |
| `SourceId` | string | UUID конкретного экземпляра ошибки |
| `TimeStamp` | string | ISO-8601 с TZ-offset, точность до 100ns (.NET ticks) |
| `Properties` | object<string,string> | свободный KV; обычно содержит `Type` = .NET тип исключения |
| `InnerException` | object \| null | вложенное исключение той же структуры |

**Правило маппинга на стороне получателя:**
- `Code` непустой → бизнес-исключение (доменная ошибка, должна быть обработана) ;
- `Code` пустой/null → техническое исключение (инфраструктурный сбой).

### 4.4. Context

`Context` присутствует и в Completed-, и в Failed-событиях. Структура одинаковая:

```json
{
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
```

| Поле | Тип | Формат | Обязательность |
|---|---|---|---|
| `CommandType` | string | FullName команды (без assembly) | обязательно |
| `CorrelationId` | string | UUID исходной команды | обязательно |
| `SourceServiceId` | string | имя адаптера-вызывателя | обязательно |
| `TimeStamp` | string | `yyyy-MM-ddTHH:mm:ss` **БЕЗ TZ, БЕЗ Z, БЕЗ долей секунды** | обязательно |
| `Priority` | string | имя enum (`"Normal"`, `"High"`, ...) — не число! | обязательно |
| `ExcutionServiceId` | string | имя handler-адаптера. **Опечатка `Excution` (без 'e') — часть протокола, менять нельзя** | обязательно |
| `ExcutionTimeStamp` | string | `yyyy-MM-ddTHH:mm:ss.fffff+HH:mm` (с TZ-offset) | обязательно |
| `ExcutionDuration` | string | формат **.NET TimeSpan** `HH:mm:ss.fffffff` (7 знаков долей — .NET ticks, 100 ns), а не ISO-8601 `PT...S` | обязательно |
| `SessionId` | string | ID сессии (если есть в Session-данных) | строка, может быть `""` |
| `OperationId` | string | ID операции (как правило, дублирует `message_id` исходной команды) | строка, может быть `""` |

#### 4.4.1. Опечатки — часть wire-протокола

`ExcutionServiceId`, `ExcutionTimeStamp`, `ExcutionDuration` — это **именно так,
без буквы 'e'** (`Execution`). Это историческая опечатка в .NET-исходниках, закрепившаяся
в проводе. Любая «исправленная» версия (`ExecutionServiceId`) будет несовместима.

#### 4.4.2. Формат TimeSpan

`ExcutionDuration` — формат `HH:mm:ss.fffffff`:

```
00:00:00.2392809   = 239 280 900 нс = 239.28 мс
01:23:45.1234567   = 1 ч 23 мин 45 с + 123.4567 мс
```

Алгоритм формирования из наносекунд `nanos`:

```
hours    = nanos / 3_600_000_000_000
minutes  = (nanos % 3_600_000_000_000) / 60_000_000_000
seconds  = (nanos % 60_000_000_000) / 1_000_000_000
ticks    = (nanos % 1_000_000_000) / 100        # 1 tick = 100 ns
output   = sprintf("%02d:%02d:%02d.%07d", hours, minutes, seconds, ticks)
```

Парсинг — обратный.

#### 4.4.3. Часовой пояс

Production-стенды живут в `+03:00` (Москва). В реальных дампах все timestamps —
с этим offset. Реализация может либо хардкодить `+03:00`, либо брать локальный TZ —
.NET-сторона разбирает оба варианта.

---

## Часть V. Type system

### 5.1. AssemblyQualifiedName

`content_type` несёт **.NET AssemblyQualifiedName** (упрощённая версия — без версии и токена культуры):

```
<FullName>, <AssemblyName>
```

Примеры:

```
TCB.MercLibrary.Client.Commands.GetConfigurationCommand, TCB.MercLibrary.Client
TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure
TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure
```

Где:
- `FullName` — полное имя типа с namespace, **без assembly-части** (используется как `routing_key`);
- `AssemblyName` — простое имя assembly (без `.dll`).

Парсер: split по первой `,`, trim пробелы.

### 5.2. Сопоставление типов

Каждая сторона должна поддерживать **двунаправленный реестр** `wireName ↔ localType`:

- при отправке команды: `localType → (FullName, AssemblyName)` для заполнения `content_type` и `routing_key`;
- при приёме ответа: `ResultType` (поле в body) → `localType` для десериализации `Result`;
- при приёме команды: `routing_key` (он же FullName) → `localType` для десериализации body.

Для не-.NET реализаций предлагается явная декларация маппинга (например, аннотация
`@WireName(value="...", assembly="...")`). Никакого автоматического вывода имени из
package/namespace — иначе несовместимость с .NET-наименованиями.

### 5.3. JSON naming

- Поля **обёрток** (`ResultType`, `Result`, `AdditionalData`, `Context`, `ExceptionData`,
  все поля Context и ExceptionData) — строго PascalCase.
- Поля **Result** и **тела команды** — как объявлено в исходном .NET-классе. Никакая
  глобальная стратегия (snake_case, camelCase) не применяется. Реализация должна уметь
  читать/писать ровно те имена, что сериализует .NET.
- Десериализатор должен быть лоялен к регистру (`ACCEPT_CASE_INSENSITIVE_PROPERTIES`)
  для устойчивости к мелким различиям.

### 5.4. Типы значений

Используется стандартный JSON. Но есть две .NET-специфики:

- **`TimeSpan`** в JSON — строка `HH:mm:ss.fffffff` (см. §4.4.2). Не путать с ISO PT-формой.
- **`DateTime` / `DateTimeOffset`** — ISO-8601, варианты:
  - без offset: `"2026-04-13T11:47:54"` (.NET DateTime, Unspecified);
  - с offset: `"2026-04-13T11:47:54.50428+03:00"` (.NET DateTimeOffset);
  - редко с `Z` (UTC).

Реализация должна уметь и читать, и писать оба варианта (см. таблицу полей Context).

---

## Часть VI. Очереди — naming convention

| Тип очереди | Имя | Биндинги |
|---|---|---|
| Результаты адаптера | `cmd.<adapterName>` | (CompletedEvent, key=`adapterName`), (FailedEvent, key=`adapterName`) |
| Handler команды | `Command_<FullName>` | (CommandExchange, key=`FullName`) |

Все очереди: `durable=true`, `exclusive=false`, `auto-delete=false`.

Имена чувствительны к регистру. Разделитель в `Command_*` — подчёркивание (`_`), не точка.
Никаких суффиксов с версией или окружением.

### 6.1. DLQ

Стандартного DLX-механизма протокол не требует. Реализация может настроить свой DLX
(`x-dead-letter-exchange`) поверх `Command_*`-очередей для обработки poison-message-ов.
Для совместимости со старым .NET-консьюмером это прозрачно.

---

## Часть VII. Session

`Session` — это разделяемое key-value-хранилище, которое путешествует с командой и
обновляется адаптерами по пути. На уровне протокола — Base64-строка в `Additional-Data.Session`.

### 7.1. Логическая модель

Внутри — JSON-объект (произвольная вложенность). Обычно содержит ключи вроде
`SessionId`, `OperationId`, доменные данные сессии.

### 7.2. Wire-формат

Сериализация (точный алгоритм):

```
1. session: object       → mapper.writeValueAsString(session)  → json: string
2. utf8 = json.getBytes(UTF-8)
3. buf = []
4. write7BitEncodedInt(buf, len(utf8))   # 1..5 байт
5. buf.append(utf8)                       # сами байты
6. compressed = DEFLATE(buf, raw=true)    # raw deflate, БЕЗ zlib-обёртки!
7. base64 = Base64.encode(compressed)
```

Десериализация — обратная:

```
1. compressed = Base64.decode(base64)
2. buf = INFLATE(compressed, raw=true)
3. (length, rest) = read7BitEncodedInt(buf)
4. utf8 = first `length` bytes of rest
5. json = utf8.decode(UTF-8)
6. session = mapper.readTree(json)
```

### 7.3. 7-bit encoded int (.NET BinaryWriter format)

Variable-length целое, little-endian по семибиткам. Старший бит каждого байта
сигнализирует «есть ещё байты».

Кодирование:

```
def write_7bit(out, value):
    while value >= 0x80:
        out.write_byte((value & 0x7F) | 0x80)
        value >>= 7
    out.write_byte(value)
```

Декодирование:

```
def read_7bit(stream):
    result = 0
    shift = 0
    while True:
        b = stream.read_byte()
        result |= (b & 0x7F) << shift
        shift += 7
        if (b & 0x80) == 0:
            return result
        if shift > 35:
            raise "bad 7-bit int"
```

Длины сообщений:

| Значение | Байт |
|---|---|
| `0..127` | 1 |
| `128..16383` | 2 |
| `16384..2097151` | 3 |
| `2097152..268435455` | 4 |
| `>= 268435456` | 5 |

### 7.4. Raw DEFLATE vs zlib

**Это самое частое место отказа межсистемного обмена.** .NET `DeflateStream` пишет
**raw DEFLATE** (RFC 1951) — без zlib-обёртки (RFC 1950, 2 байта `0x78 0x9C` + 4 байта
adler32-checksum).

| Платформа | API | Контейнер |
|---|---|---|
| .NET | `System.IO.Compression.DeflateStream` | raw DEFLATE |
| Java | `Deflater(level)` по умолчанию | zlib |
| Java | `Deflater(level, nowrap=true)` | raw DEFLATE ✅ |
| Python | `zlib.compress()` | zlib |
| Python | `zlib.compressobj(level, zlib.DEFLATED, -zlib.MAX_WBITS)` | raw DEFLATE ✅ |
| Python | `gzip` | gzip-контейнер ❌ |
| Node.js | `zlib.deflate()` | zlib |
| Node.js | `zlib.deflateRaw()` | raw DEFLATE ✅ |
| Go | `compress/flate.NewWriter()` | raw DEFLATE ✅ |
| Go | `compress/zlib.NewWriter()` | zlib ❌ |

Если .NET-приёмник падает с сообщением:

```
System.IO.Compression.Inflater.DecodeUncompressedBlock:
Block length does not match with its complement.
```

— это значит, что отправитель прислал zlib вместо raw DEFLATE.

### 7.5. Обработка ошибок при десериализации

Если Base64/DEFLATE/UTF-8/JSON-парсинг сессии провалился — реализация должна вернуть
**пустой объект** (не null, не исключение) и залогировать warning. Это поведение .NET-стороны:
повреждённая сессия деградирует до «пустой fake-сессии», команда продолжает обрабатываться.

### 7.6. Дельта-механика (опционально)

.NET-сессия трекает изменённые ключи (`changedKeys`). При записи можно слать только
дельту через `getChangedData()`. Это деталь реализации, на проводе видна как обычная
сериализованная сессия.

---

## Часть VIII. Корреляция и тайм-ауты

### 8.1. Correlation store

Адаптер-вызыватель должен поддерживать структуру:

```
correlationId → (future, expectedResultType, expireAt)
```

При publish команды — регистрирует запись. При приёме сообщения в `cmd.<adapter>`:

1. Берёт `correlation_id` из properties.
2. Ищет запись. Если нет — игнорирует (warning) — это либо протухший ответ, либо чужой.
3. По `content_type` определяет: Completed или Failed.
4. Если Completed — десериализует `body.Result` в `expectedResultType`, резолвит future.
5. Если Failed — мапит `body.ExceptionData` в исключение (см. §4.3), завершает future ошибкой.
6. Удаляет запись.

### 8.2. Тайм-ауты

Протокол сам по себе тайм-аутов не диктует. Каждая реализация выбирает дефолт
(в Java-имплементации: `defaultTimeout = 30s`). Sweeper-поток периодически (например,
раз в 30 секунд) проходит по correlation-store и завершает истёкшие future с
TimeoutException, освобождая память.

### 8.3. Дубликаты и at-least-once

RMQ гарантирует at-least-once. Получатель обязан быть идемпотентен **по correlationId**:
повторный приём одного и того же ответа не должен ломать состояние (просто warning,
если future уже завершён).

---

## Часть IX. Семантика приёма команды (handler-side)

Полный алгоритм handler-адаптера при приёме сообщения из `Command_<FullName>`:

```
1. Прочитать AMQP envelope/properties/headers/body.
2. Распарсить headers.Additional-Data как JSON-строку → map.
3. Извлечь sourceServiceId = map["SourceServiceId"].   // обратный адрес
4. Извлечь sessionBase64   = map["Session"].           // может отсутствовать
5. Десериализовать sessionBase64 → ObjectNode (см. §7).
6. По routing_key найти localType команды в реестре.
7. Десериализовать body как localType.
8. Старт таймера (для ExcutionDuration).
9. Bind ThreadLocal/ContextLocal на сессию.
10. Вызвать handler(command [, context]).
11. На УСПЕХ:
      a. duration = elapsed
      b. Получить resultType = AssemblyQualifiedName(result.class)
      c. Сериализовать сессию (см. §7) — она могла измениться в handler-е
      d. Построить body Completed-события (§4.1.4) с Context (§4.4)
      e. Publish в TCB.Infrastructure.Command.CommandCompletedEvent
         routing_key = sourceServiceId
         correlation_id = тот же, что в команде
         message_id = ++localCounter
12. На ОШИБКУ:
      a. Построить InfrastructureExceptionDto из исключения (§4.3)
         - Code: непустой если business, "" если technical
      b. Publish в TCB.Infrastructure.Command.CommandFailedEvent
         (остальное аналогично)
13. Ack входящего сообщения (auto-ack или ручной — выбор реализации).
```

### 9.1. Поведение при отсутствии handler-а

Если в `Command_<FullName>`-очередь пришло сообщение, на которое нет зарегистрированного
handler-а в этом адаптере, — реализация должна либо `nack` (с retry или в DLQ), либо
ответить `CommandFailedEvent` с `ExceptionData.Code = "HandlerNotFound"`. Конкретику
определяет адаптер, но **молча терять сообщение нельзя**.

### 9.2. Политика обработки ошибок (`@CommandHandler.onError`)

Java-реализация поддерживает две политики:

- `REPLY_WITH_FAILURE` (default) — отправить `CommandFailedEvent`, ack команду;
- `REJECT_TO_DLQ` — `nack` без отправки события, чтобы сработал DLX-механизм брокера.

На проводе политики неотличимы; это локальный выбор handler-адаптера.

---

## Часть X. Семантика отправки (caller-side)

```
1. command : LocalCommandType
2. correlationId = newUUIDv4().toString()
3. messageId    = String(localCounter++)
4. timestamp    = now (epoch seconds)
5. content_type = AssemblyQualifiedName(command.class) из реестра
6. routing_key  = FullName(command.class)
7. additional   = {"Session": serialize(currentSession), "IsCommand": "", "SourceServiceId": myAdapterName}
8. headers      = {"Accept-Language": "ru-RU", "Additional-Data": JSON.stringify(additional)}
9. body         = JSON.stringify(command)
10. (опц.) проверить через RMQ Mgmt API существование Command_<routing_key>; если нет — fail-fast
11. publish в CommandExchange с routing_key
12. registerCorrelation(correlationId, future, expectedResultType, expireAt = now + timeout)
13. return future
```

---

## Часть XI. Версионирование и совместимость

- Протокол **не имеет номера версии в сообщениях**. Совместимость поддерживается на уровне
  типов: новая версия команды = новый тип с новым FullName.
- Расширение `Additional-Data` новыми ключами совместимо назад: неизвестные ключи
  игнорируются.
- Расширение `Context`, `ExceptionData` новыми полями совместимо назад при условии, что
  получатель десериализует с lenient-настройкой (`FAIL_ON_UNKNOWN_PROPERTIES = false`).
- Изменение существующих имён полей (включая опечатки `Excution*`) — **ломающее**.
- Удаление обязательных полей (`Context`, `ResultType`, `correlation_id`) — **ломающее**.

---

## Часть XII. Безопасность и эксплуатация

### 12.1. Аутентификация / авторизация

Только на уровне RMQ: `vhost`, `user`, `password`. Никаких токенов в сообщениях.
Адаптер должен иметь права `configure/write/read` на префиксы `cmd.*` и `Command_*` и на
три SAL-обменника.

### 12.2. TLS

На уровне AMQP-соединения (`amqps://`). Содержимое сообщений не шифруется протоколом.

### 12.3. Чувствительные данные

`Session` может содержать персональные данные. На уровне протокола они идут в открытом виде
(Base64 — не шифрование). За шифрование/маскирование отвечает приложение.

### 12.4. Confirmations

Для гарантий «at-least-once» рекомендуется включать **publisher confirms** на стороне
caller-а. Транзакции (`tx.select`) — не использовать, медленно.

---

## Часть XIII. Отладка / диагностика

### 13.1. Минимальный probe

Чтобы захватить сырой трафик, можно:

1. Через RMQ Management API получить список биндингов на `CommandExchange`,
   `CommandCompletedEvent`, `CommandFailedEvent`.
2. Объявить временную auto-delete-очередь.
3. Забиндить её с теми же routing-ключами, что и существующие очереди (поскольку
   exchange — direct, wildcard `#`/`*` не работают, нужно копировать байндинги).
4. Сохранять каждое сообщение как JSON-дамп со всеми полями (envelope/properties/headers/body).

Готовый референс: `java-commands/sal-commands-wire-probe/`.

### 13.2. Контрольные вопросы при отладке несовместимости

- `Additional-Data` — это **строка с JSON**, а не nested map?
- `content_type` несёт **AssemblyQualifiedName**, а не FullName?
- `routing_key` команды — это **FullName** (без assembly)?
- `routing_key` ответа — это **SourceServiceId исходной команды**, не `correlation_id`?
- Все поля Context — PascalCase, опечатки `Excution*` сохранены?
- `TimeStamp` — без TZ; `ExcutionTimeStamp` — с TZ-offset?
- `ExcutionDuration` — `HH:mm:ss.fffffff`, не ISO `PT...S`?
- `Priority` в Context — строка-имя (`"Normal"`), не число?
- Session: `nowrap=true` для DEFLATE? `7BitEncodedInt` перед UTF-8?
- Очередь handler-а названа `Command_<FullName>` (с подчёркиванием и точным регистром)?
- `message_id` — числовая строка, а не UUID?

---

## Приложение A. Полный реальный пример (round-trip)

### A.1. Команда (caller → handler)

```
exchange    : CommandExchange
routingKey  : TCB.MercLibrary.Client.Commands.GetConfigurationCommand
properties:
  contentType    : "TCB.MercLibrary.Client.Commands.GetConfigurationCommand, TCB.MercLibrary.Client"
  deliveryMode   : 2
  priority       : 5
  correlationId  : "060afc81-1b8e-478a-bdfe-d5e949d395c0"
  messageId      : "171478030"
  timestamp      : 1776080874
headers:
  Accept-Language : "ru-RU"
  Additional-Data : "{\"Session\":\"bZTbsqpGEIYfIG/B...AA==\",\"IsCommand\":\"\",\"SourceServiceId\":\"WorkFlowMashineMain\"}"
body (UTF-8):
  {"MercID":"103337"}
```

### A.2. CommandCompletedEvent (handler → caller)

```
exchange    : TCB.Infrastructure.Command.CommandCompletedEvent
routingKey  : WorkFlowMashineMain
properties:
  contentType    : "TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure"
  deliveryMode   : 2
  priority       : 5
  correlationId  : "060afc81-1b8e-478a-bdfe-d5e949d395c0"
  messageId      : "3641"
  timestamp      : 1776080874
headers:
  Accept-Language : "ru-RU"
  Additional-Data : "{\"Session\":\"bZTbsqpGEIYfIG/B...AA==\",\"SourceServiceId\":\"TCB.MercLibrary\"}"
body (UTF-8, отформатировано):
  {
    "ResultType": "TCB.MercLibrary.Client.Results.GetConfigurationCommandResult, TCB.MercLibrary.Client",
    "Result": {
      "Name": "Наймикс",
      "Account": "40701810820020300021",
      "Currency": "643",
      ...
    },
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

---

## Приложение B. Чеклист реализации на новом языке

- [ ] AMQP 0-9-1 клиент с поддержкой publisher confirms.
- [ ] Объявление трёх direct-durable обменников и собственной `cmd.<adapter>`-очереди.
- [ ] Реестр типов с явной декларацией `(localType ↔ FullName, AssemblyName)`.
- [ ] `RecordedMessage`-конвертер: разделение метаданных по AMQP полям, body = чистый JSON.
- [ ] Парсер/сериализатор `Additional-Data` как JSON-строки внутри AMQP-заголовка.
- [ ] Реализация `7BitEncodedInt` (write/read).
- [ ] Raw DEFLATE (nowrap) для Session.
- [ ] Десериализатор JSON, не падающий на неизвестных полях, с case-insensitive именами.
- [ ] Сериализатор/парсер .NET TimeSpan (`HH:mm:ss.fffffff`).
- [ ] Сериализатор `DateTime` (без TZ) и `DateTimeOffset` (с TZ-offset).
- [ ] Correlation store с тайм-аутами и sweeper-ом.
- [ ] Маппинг `InfrastructureExceptionDto` → доменные исключения по непустоте `Code`.
- [ ] Объявление `Command_<FullName>`-очередей при регистрации handler-ов.
- [ ] Handler-pipeline с заполнением `ExcutionServiceId`/`ExcutionTimeStamp`/`ExcutionDuration` (опечатки сохранять!).
- [ ] Round-trip-тест против реального .NET-стенда (golden message replay).
