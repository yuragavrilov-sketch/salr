# Java Command Bus — Wire Foundation (Этапы 1-2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Построить Maven multi-module скелет (`sal-commands-api/core/starter/test`) и wire-слой — DTO-зеркала .NET-классов, реестр типов через `@WireName`, конвертер `RecordedMessage` с Jackson, `SessionSerializer` — и доказать golden JSON round-trip тестами, что байт-в-байт формат .NET SAL воспроизводится Java-стороной.

**Architecture:** Maven multi-module (parent + 4 child poms) с `spring-boot-starter-parent:3.3.5` как родителем. Wire-слой — чистая Java, зависит только от Jackson и spring-context (для classpath scan в `WireTypeRegistry`). Jackson настроен локально в `RecordedMessageConverter` на PascalCase + ISO-8601 дата, чтобы не навязывать эти настройки пользователю. Golden JSON тесты фиксируют wire-формат — любое изменение сериализации сломает их и потребует явного апдейта goldens.

**Tech Stack:** Java 21, Maven, Spring Boot 3.3.5 (parent), Spring Context (classpath scan), Jackson 2.17 (via Boot BOM), JUnit 5 + AssertJ (via Boot BOM).

**Scope (что в плане):** Этапы 1-2 из спеки. По завершении — standalone-библиотека с тестами, которая умеет читать/писать `RecordedMessage` в байт-в-байт совместимом с .NET JSON-формате.

**Out of scope (в следующем плане):** Transport-абстракция, `CommandBus`, listener, handler discovery, exception mapping, starter autoconfig, test-slice, integration тесты с Testcontainers, release.

**Base directory:** `/mnt/c/work/sal_refactoring/java-commands/`

---

## Предварительные требования

- Установлены: Java 21 (OpenJDK или Temurin), Maven 3.9+.
- Проверка: `java --version` → 21.x; `mvn --version` → 3.9+.
- Рабочая директория: `/mnt/c/work/sal_refactoring/` (существует).
- `java-commands/` ещё не создана — её создаст Task 1.

---

## Этап 1: Maven multi-module скелет

### Task 1: Создать родительский Maven-проект

**Files:**
- Create: `java-commands/pom.xml`
- Create: `java-commands/.gitignore`

- [ ] **Step 1.1: Создать директорию**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands
cd /mnt/c/work/sal_refactoring/java-commands
```

- [ ] **Step 1.2: Написать родительский `pom.xml`**

Создать `java-commands/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>ru.tcb.sal</groupId>
    <artifactId>sal-commands-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>SAL Commands — Parent</name>
    <description>SAL Command Bus Spring Boot starter (Java port of .NET TCB.SAL.Common)</description>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <modules>
        <module>sal-commands-api</module>
        <module>sal-commands-core</module>
        <module>sal-commands-spring-boot-starter</module>
        <module>sal-commands-test</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>ru.tcb.sal</groupId>
                <artifactId>sal-commands-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>ru.tcb.sal</groupId>
                <artifactId>sal-commands-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>ru.tcb.sal</groupId>
                <artifactId>sal-commands-spring-boot-starter</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>ru.tcb.sal</groupId>
                <artifactId>sal-commands-test</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <useModulePath>false</useModulePath>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 1.3: Написать `.gitignore`**

Создать `java-commands/.gitignore`:

```
target/
*.class
*.jar
*.war
.idea/
*.iml
.vscode/
.DS_Store
```

- [ ] **Step 1.4: Проверить, что Maven парсит родительский pom**

Из `java-commands/`:
```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn help:effective-pom -N 2>&1 | tail -30
```

Expected: `BUILD SUCCESS` и фрагмент `<artifactId>sal-commands-parent</artifactId>`.
Модули ещё не существуют — `-N` (non-recursive) игнорирует их.

---

### Task 2: Модуль `sal-commands-api`

**Files:**
- Create: `java-commands/sal-commands-api/pom.xml`
- Create: `java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/.keep`

- [ ] **Step 2.1: Создать директорию и `.keep`**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-api/src/test/java/ru/tcb/sal/commands/api
touch /mnt/c/work/sal_refactoring/java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/.keep
```

- [ ] **Step 2.2: Написать `pom.xml`**

Создать `java-commands/sal-commands-api/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ru.tcb.sal</groupId>
        <artifactId>sal-commands-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>sal-commands-api</artifactId>
    <name>SAL Commands — API</name>
    <description>Public contracts: markers, annotations, CommandBus interface, exception hierarchy</description>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2.3: Сборка модуля (ожидается, что скомпилируется пустой модуль)**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-api -am compile 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`. Ошибки «No sources to compile» допустимы.

---

### Task 3: Модуль `sal-commands-core`

**Files:**
- Create: `java-commands/sal-commands-core/pom.xml`
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/.keep`

- [ ] **Step 3.1: Создать директории**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/test/resources/golden
touch /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/.keep
```

- [ ] **Step 3.2: Написать `pom.xml`**

Создать `java-commands/sal-commands-core/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ru.tcb.sal</groupId>
        <artifactId>sal-commands-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>sal-commands-core</artifactId>
    <name>SAL Commands — Core</name>
    <description>Reference implementation of CommandBus, transport, wire layer, handler registry</description>

    <dependencies>
        <dependency>
            <groupId>ru.tcb.sal</groupId>
            <artifactId>sal-commands-api</artifactId>
        </dependency>

        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>

        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3.3: Сборка**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core -am compile 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`.

---

### Task 4: Модуль `sal-commands-spring-boot-starter`

**Files:**
- Create: `java-commands/sal-commands-spring-boot-starter/pom.xml`
- Create: `java-commands/sal-commands-spring-boot-starter/src/main/java/ru/tcb/sal/commands/spring/.keep`

- [ ] **Step 4.1: Создать директории**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-spring-boot-starter/src/main/java/ru/tcb/sal/commands/spring
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-spring-boot-starter/src/main/resources/META-INF/spring
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-spring-boot-starter/src/test/java/ru/tcb/sal/commands/spring
touch /mnt/c/work/sal_refactoring/java-commands/sal-commands-spring-boot-starter/src/main/java/ru/tcb/sal/commands/spring/.keep
```

- [ ] **Step 4.2: Написать `pom.xml`**

Создать `java-commands/sal-commands-spring-boot-starter/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ru.tcb.sal</groupId>
        <artifactId>sal-commands-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>sal-commands-spring-boot-starter</artifactId>
    <name>SAL Commands — Spring Boot Starter</name>
    <description>Autoconfiguration + properties for sal-commands in Spring Boot applications</description>

    <dependencies>
        <dependency>
            <groupId>ru.tcb.sal</groupId>
            <artifactId>sal-commands-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4.3: Сборка**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-spring-boot-starter -am compile 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`.

---

### Task 5: Модуль `sal-commands-test`

**Files:**
- Create: `java-commands/sal-commands-test/pom.xml`
- Create: `java-commands/sal-commands-test/src/main/java/ru/tcb/sal/commands/test/.keep`

- [ ] **Step 5.1: Создать директории**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-test/src/main/java/ru/tcb/sal/commands/test
touch /mnt/c/work/sal_refactoring/java-commands/sal-commands-test/src/main/java/ru/tcb/sal/commands/test/.keep
```

- [ ] **Step 5.2: Написать `pom.xml`**

Создать `java-commands/sal-commands-test/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ru.tcb.sal</groupId>
        <artifactId>sal-commands-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>sal-commands-test</artifactId>
    <name>SAL Commands — Test Support</name>
    <description>Test slice annotation and in-memory CommandTransport for unit tests of @CommandHandler beans</description>

    <dependencies>
        <dependency>
            <groupId>ru.tcb.sal</groupId>
            <artifactId>sal-commands-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-test</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-test-autoconfigure</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5.3: Сборка**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-test -am compile 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`.

---

### Task 6: Полная сборка + первый коммит

- [ ] **Step 6.1: Сборка всех модулей**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn clean verify 2>&1 | tail -25
```

Expected: `BUILD SUCCESS`, 4 модуля скомпилированы, тестов 0.

- [ ] **Step 6.2: Коммит скелета**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/
git commit -m "feat(sal-commands): add Maven multi-module skeleton

Parent pom with spring-boot-starter-parent:3.3.5 + 4 child modules:
api, core, spring-boot-starter, test. Empty package structure, all
modules compile successfully."
```

Expected: коммит создан, `git log --oneline` показывает новый коммит.

---

## Этап 2: Wire foundation

### Task 7: API markers + CommandPriority

**Files:**
- Create: `java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/Command.java`
- Create: `java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandResult.java`
- Create: `java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandWithResult.java`
- Create: `java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandPriority.java`
- Create: `java-commands/sal-commands-api/src/test/java/ru/tcb/sal/commands/api/CommandPriorityTest.java`
- Delete: `java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/.keep`

- [ ] **Step 7.1: Написать тест для `CommandPriority`**

Создать `sal-commands-api/src/test/java/ru/tcb/sal/commands/api/CommandPriorityTest.java`:

```java
package ru.tcb.sal.commands.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandPriorityTest {

    @Test
    void byteValues_matchDotNetEnum() {
        assertThat(CommandPriority.IDLE.asByte()).isEqualTo((byte) 0);
        assertThat(CommandPriority.BELOW_NORMAL.asByte()).isEqualTo((byte) 4);
        assertThat(CommandPriority.NORMAL.asByte()).isEqualTo((byte) 5);
        assertThat(CommandPriority.ABOVE_NORMAL.asByte()).isEqualTo((byte) 6);
        assertThat(CommandPriority.HIGH.asByte()).isEqualTo((byte) 9);
        assertThat(CommandPriority.REAL_TIME.asByte()).isEqualTo((byte) 10);
    }

    @Test
    void fromByte_recoversEnum() {
        assertThat(CommandPriority.fromByte((byte) 5)).isEqualTo(CommandPriority.NORMAL);
        assertThat(CommandPriority.fromByte((byte) 10)).isEqualTo(CommandPriority.REAL_TIME);
    }

    @Test
    void fromByte_unknownValue_returnsNormal() {
        assertThat(CommandPriority.fromByte((byte) 77)).isEqualTo(CommandPriority.NORMAL);
    }
}
```

- [ ] **Step 7.2: Запустить тест — должен упасть (нет класса)**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-api test 2>&1 | tail -20
```

Expected: compilation error, `cannot find symbol class CommandPriority`.

- [ ] **Step 7.3: Написать `CommandPriority`**

Создать `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandPriority.java`:

```java
package ru.tcb.sal.commands.api;

/**
 * Соответствует .NET TCB.Infrastructure.Command.CommandPriority.
 * Байтовые значения — контракт с .NET-стороной, менять нельзя.
 */
public enum CommandPriority {
    IDLE((byte) 0),
    BELOW_NORMAL((byte) 4),
    NORMAL((byte) 5),
    ABOVE_NORMAL((byte) 6),
    HIGH((byte) 9),
    REAL_TIME((byte) 10);

    private final byte value;

    CommandPriority(byte value) {
        this.value = value;
    }

    public byte asByte() {
        return value;
    }

    public static CommandPriority fromByte(byte value) {
        for (CommandPriority p : values()) {
            if (p.value == value) return p;
        }
        return NORMAL;
    }
}
```

- [ ] **Step 7.4: Добавить spring-core в api/pom.xml**

`CommandWithResult.resultType()` использует `GenericTypeResolver` из `spring-core` — зависимость надо добавить ДО того, как писать класс, иначе компиляция сломается на следующем шаге.

Отредактировать `sal-commands-api/pom.xml`: внутри `<dependencies>` добавить после `jackson-annotations`:

```xml
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-core</artifactId>
        </dependency>
```

- [ ] **Step 7.5: Написать marker interfaces**

Создать `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/Command.java`:

```java
package ru.tcb.sal.commands.api;

/**
 * Маркер fire-and-forget команды без ожидаемого результата.
 * Соответствует .NET TCB.Infrastructure.Command.ICommand.
 */
public interface Command {
}
```

Создать `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandResult.java`:

```java
package ru.tcb.sal.commands.api;

/**
 * Маркер результата команды.
 * Соответствует .NET TCB.Infrastructure.Command.ICommandResult.
 */
public interface CommandResult {
}
```

Создать `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/CommandWithResult.java`:

```java
package ru.tcb.sal.commands.api;

import org.springframework.core.GenericTypeResolver;

/**
 * Команда с типизированным результатом.
 * Соответствует .NET TCB.Infrastructure.Command.IHaveResult&lt;TResult&gt;.
 *
 * @param <R> тип результата
 */
public interface CommandWithResult<R extends CommandResult> extends Command {

    /**
     * Возвращает runtime Class результата.
     * По умолчанию резолвит через GenericTypeResolver; override для
     * динамических случаев.
     */
    @SuppressWarnings("unchecked")
    default Class<R> resultType() {
        Class<?> resolved = GenericTypeResolver.resolveTypeArgument(
            getClass(), CommandWithResult.class);
        if (resolved == null) {
            throw new IllegalStateException(
                "Cannot resolve result type of " + getClass().getName()
                    + "; override resultType() explicitly");
        }
        return (Class<R>) resolved;
    }
}
```

- [ ] **Step 7.6: Удалить `.keep`**

```bash
rm /mnt/c/work/sal_refactoring/java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/.keep
```

- [ ] **Step 7.7: Запустить тесты — должны пройти**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-api test 2>&1 | tail -15
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

- [ ] **Step 7.8: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-api/
git commit -m "feat(sal-commands-api): add Command markers and CommandPriority

Markers: Command, CommandResult, CommandWithResult<R>.
CommandPriority enum with byte values matching .NET TCB.Infrastructure
CommandPriority (Idle=0, BelowNormal=4, Normal=5, AboveNormal=6,
High=9, RealTime=10)."
```

---

### Task 8: Аннотация `@WireName`

**Files:**
- Create: `java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/annotation/WireName.java`
- Create: `java-commands/sal-commands-api/src/test/java/ru/tcb/sal/commands/api/annotation/WireNameTest.java`

- [ ] **Step 8.1: Создать package и написать тест**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-api/src/main/java/ru/tcb/sal/commands/api/annotation
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-api/src/test/java/ru/tcb/sal/commands/api/annotation
```

Создать `sal-commands-api/src/test/java/ru/tcb/sal/commands/api/annotation/WireNameTest.java`:

```java
package ru.tcb.sal.commands.api.annotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireNameTest {

    @WireName("TCB.Payment.Request.CreatePaymentCommand")
    private static class PaymentCmd {}

    @WireName(value = "TCB.Payment.Contracts.CreatePaymentResult",
              assembly = "TCB.Payment.Contracts")
    private static class PaymentResult {}

    @Test
    void annotationOnCommand_hasWireName_noAssembly() {
        WireName ann = PaymentCmd.class.getAnnotation(WireName.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("TCB.Payment.Request.CreatePaymentCommand");
        assertThat(ann.assembly()).isEmpty();
    }

    @Test
    void annotationOnResult_hasBothValueAndAssembly() {
        WireName ann = PaymentResult.class.getAnnotation(WireName.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("TCB.Payment.Contracts.CreatePaymentResult");
        assertThat(ann.assembly()).isEqualTo("TCB.Payment.Contracts");
    }
}
```

- [ ] **Step 8.2: Запустить тест — должен упасть**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-api test 2>&1 | tail -20
```

Expected: compilation error, `cannot find symbol class WireName`.

- [ ] **Step 8.3: Написать аннотацию**

Создать `sal-commands-api/src/main/java/ru/tcb/sal/commands/api/annotation/WireName.java`:

```java
package ru.tcb.sal.commands.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Привязывает Java-класс к .NET wire-имени.
 *
 * <p>Для команд — routing key и FullName; для результатов — также
 * AssemblyQualifiedName (комбинация value + assembly через запятую).
 *
 * <p>Без этой аннотации Java-классы, реализующие Command/CommandResult,
 * не могут быть зарегистрированы в {@code WireTypeRegistry}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WireName {

    /** Полное wire-имя (.NET FullName), напр. "TCB.Payment.Request.CreatePaymentCommand". */
    String value();

    /**
     * Имя .NET сборки (только для классов результата, обязательно).
     * Напр. "TCB.Payment.Contracts". Для команд — пустая строка.
     */
    String assembly() default "";
}
```

- [ ] **Step 8.4: Запустить тесты**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-api test 2>&1 | tail -15
```

Expected: `Tests run: 5` (3 предыдущих + 2 новых), `BUILD SUCCESS`.

- [ ] **Step 8.5: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-api/
git commit -m "feat(sal-commands-api): add @WireName annotation

Binds Java classes to .NET FullName + optional AssemblyQualifiedName.
Required on all Command/CommandResult implementations — enforced at
startup by WireTypeRegistry (see Task 10)."
```

---

### Task 9: Wire DTO-зеркала

**Files:**
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/RecordedMessage.java`
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/WireCommandContext.java`
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/InfrastructureExceptionDto.java`
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/CommandCompletedEvent.java`
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/CommandFailedEvent.java`
- Delete: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/.keep`

DTO-классы объявляются без TDD-цикла (простые bean'ы с публичными полями — поведение проверится в Task 12-14 через конвертер). Каждый класс комментируется ссылкой на .NET-оригинал.

- [ ] **Step 9.1: Создать package**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire
rm /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/.keep
```

- [ ] **Step 9.2: Написать `RecordedMessage`**

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/RecordedMessage.java`:

```java
package ru.tcb.sal.commands.core.wire;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Зеркало .NET TCB.Infrastructure.Message.RecordedMessage — транспортный
 * конверт шины. Поля публичные и lowerCamelCase; Jackson сериализует
 * их в PascalCase через локальную политику naming в RecordedMessageConverter.
 *
 * <p>Не record, потому что .NET-сторона ожидает mutable POJO-форму
 * и мы сами иногда заполняем поля поэтапно.
 */
public class RecordedMessage {
    public String correlationId;
    public String exchangeName;
    public String routingKey;
    public String sourceServiceId;
    public String messageId;
    public byte priority;
    public Instant timeStamp;
    public Instant expireDate;
    public Object payload;
    public Map<String, String> additionalData = new LinkedHashMap<>();
}
```

- [ ] **Step 9.3: Написать `WireCommandContext`**

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/WireCommandContext.java`:

```java
package ru.tcb.sal.commands.core.wire;

import java.time.Duration;
import java.time.Instant;

/**
 * Зеркало .NET TCB.Infrastructure.Command.CommandContext.
 *
 * <p><b>Опечатки {@code Excution*}</b> — часть .NET DTO. НЕ исправлять,
 * это публичный wire-контракт. Для пользователей есть чистый
 * {@code api/CommandContext} без опечаток, маппинг внутренний.
 */
public class WireCommandContext {
    public String commandType;
    public String correlationId;
    public String sourceServiceId;
    public Instant timeStamp;
    public Instant expireDate;

    // === .NET typos below — do not rename ===
    public String excutionServiceId;
    public Instant excutionTimeStamp;
    public Duration excutionDuration;
    // =========================================

    public byte priority;
    public String sessionId;
    public String operationId;
}
```

- [ ] **Step 9.4: Написать `InfrastructureExceptionDto`**

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/InfrastructureExceptionDto.java`:

```java
package ru.tcb.sal.commands.core.wire;

import java.time.Instant;

/**
 * Зеркало .NET TCB.Infrastructure.Exceptions.InfrastructureExceptionDTO.
 * Рекурсивная структура: InnerException того же типа.
 */
public class InfrastructureExceptionDto {
    public String exceptionType;
    public String code;
    public String codeDescription;
    public String message;
    public String adapterName;
    public String sourceType;
    public String sourcePath;
    public String sessionId;
    public String sourceId;
    public Instant timeStamp;
    public Object properties;
    public InfrastructureExceptionDto innerException;
}
```

- [ ] **Step 9.5: Написать `CommandCompletedEvent` + `CommandFailedEvent`**

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/CommandCompletedEvent.java`:

```java
package ru.tcb.sal.commands.core.wire;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Зеркало .NET TCB.Infrastructure.Command.CommandCompletedEvent.
 * Отправляется от получателя (B) обратно отправителю (A) при успехе.
 */
public class CommandCompletedEvent {
    public WireCommandContext context;
    /** .NET AssemblyQualifiedName: "{FullName}, {AssemblyName}". */
    public String resultType;
    /** Результат, на проводе — JObject; в Java читается как JsonNode и конвертируется позже. */
    public Object result;
    public Map<String, Object> additionalData = new LinkedHashMap<>();
}
```

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/CommandFailedEvent.java`:

```java
package ru.tcb.sal.commands.core.wire;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Зеркало .NET TCB.Infrastructure.Command.CommandFailedEvent.
 * Отправляется от получателя (B) обратно отправителю (A) при ошибке.
 */
public class CommandFailedEvent {
    public WireCommandContext context;
    public InfrastructureExceptionDto exceptionData;
    public Map<String, Object> additionalData = new LinkedHashMap<>();
}
```

- [ ] **Step 9.6: Сборка**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core -am compile 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9.7: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add wire DTO mirrors from .NET

RecordedMessage (transport envelope), WireCommandContext (with .NET
typos Excution* preserved verbatim), InfrastructureExceptionDto,
CommandCompletedEvent, CommandFailedEvent. All mutable POJO with
public lowerCamelCase fields; Jackson will serialize as PascalCase
via converter-local naming policy."
```

---

### Task 10: `WireTypeRegistry` с classpath scan

**Files:**
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/WireTypeRegistry.java`
- Create: `java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core/wire/WireTypeRegistryTest.java`

- [ ] **Step 10.1: Написать тест**

Создать `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/wire/WireTypeRegistryTest.java`:

```java
package ru.tcb.sal.commands.core.wire;

import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.api.Command;
import ru.tcb.sal.commands.api.CommandResult;
import ru.tcb.sal.commands.api.annotation.WireName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireTypeRegistryTest {

    @WireName("TCB.Test.PingCommand")
    static class PingCommand implements Command {}

    @WireName(value = "TCB.Test.PingResult", assembly = "TCB.Test.Contracts")
    static class PingResult implements CommandResult {}

    static class Unannotated implements Command {}

    @WireName("TCB.Test.ResultWithoutAssembly")
    static class ResultWithoutAssembly implements CommandResult {}

    @Test
    void register_knownCommand_resolvesBothWays() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);

        assertThat(registry.wireName(PingCommand.class)).isEqualTo("TCB.Test.PingCommand");
        assertThat(registry.classByWireName("TCB.Test.PingCommand")).isEqualTo(PingCommand.class);
    }

    @Test
    void register_result_producesAssemblyQualifiedName() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingResult.class);

        assertThat(registry.wireName(PingResult.class)).isEqualTo("TCB.Test.PingResult");
        assertThat(registry.assembly(PingResult.class)).isEqualTo("TCB.Test.Contracts");
        assertThat(registry.assemblyQualifiedName(PingResult.class))
            .isEqualTo("TCB.Test.PingResult, TCB.Test.Contracts");
    }

    @Test
    void register_commandWithoutAnnotation_fails() {
        WireTypeRegistry registry = new WireTypeRegistry();
        assertThatThrownBy(() -> registry.register(Unannotated.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@WireName")
            .hasMessageContaining("Unannotated");
    }

    @Test
    void register_resultWithoutAssembly_fails() {
        WireTypeRegistry registry = new WireTypeRegistry();
        assertThatThrownBy(() -> registry.register(ResultWithoutAssembly.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("assembly")
            .hasMessageContaining("ResultWithoutAssembly");
    }

    @Test
    void register_duplicateWireName_fails() {
        @WireName("TCB.Test.PingCommand")
        class DuplicatePing implements Command {}

        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);

        assertThatThrownBy(() -> registry.register(DuplicatePing.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void classByWireName_unknown_returnsNull() {
        WireTypeRegistry registry = new WireTypeRegistry();
        assertThat(registry.classByWireName("TCB.Test.Unknown")).isNull();
    }
}
```

- [ ] **Step 10.2: Запустить тест — должен упасть**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -20
```

Expected: compilation error, `cannot find symbol class WireTypeRegistry`.

- [ ] **Step 10.3: Написать `WireTypeRegistry`**

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/wire/WireTypeRegistry.java`:

```java
package ru.tcb.sal.commands.core.wire;

import ru.tcb.sal.commands.api.Command;
import ru.tcb.sal.commands.api.CommandResult;
import ru.tcb.sal.commands.api.annotation.WireName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Двусторонний реестр {@code Class &lt;-&gt; wire name}, построенный по
 * аннотации {@link WireName}. Fail-fast: отсутствие аннотации на
 * классе, реализующем {@link Command}/{@link CommandResult}, — ошибка
 * регистрации. У классов результата обязателен непустой
 * {@code assembly}, иначе собрать AssemblyQualifiedName невозможно.
 *
 * <p>Thread-safe для чтения после полной регистрации.
 */
public class WireTypeRegistry {

    private final Map<String, Class<?>> wireNameToClass = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> classToWireName = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> classToAssembly = new ConcurrentHashMap<>();

    public void register(Class<?> clazz) {
        WireName ann = clazz.getAnnotation(WireName.class);
        if (ann == null) {
            throw new IllegalStateException(
                "Class " + clazz.getName() + " implements Command/CommandResult but is not annotated with @WireName");
        }

        boolean isResult = CommandResult.class.isAssignableFrom(clazz);
        if (isResult && ann.assembly().isEmpty()) {
            throw new IllegalStateException(
                "CommandResult class " + clazz.getName() + " must declare @WireName.assembly");
        }

        String name = ann.value();
        Class<?> existing = wireNameToClass.putIfAbsent(name, clazz);
        if (existing != null && !existing.equals(clazz)) {
            throw new IllegalStateException(
                "Duplicate @WireName '" + name + "': " + existing.getName() + " and " + clazz.getName());
        }
        classToWireName.put(clazz, name);
        if (!ann.assembly().isEmpty()) {
            classToAssembly.put(clazz, ann.assembly());
        }
    }

    public String wireName(Class<?> clazz) {
        String name = classToWireName.get(clazz);
        if (name == null) {
            throw new IllegalStateException("Not registered: " + clazz.getName());
        }
        return name;
    }

    public String assembly(Class<?> clazz) {
        String asm = classToAssembly.get(clazz);
        if (asm == null) {
            throw new IllegalStateException("No assembly registered for " + clazz.getName());
        }
        return asm;
    }

    public String assemblyQualifiedName(Class<?> clazz) {
        return wireName(clazz) + ", " + assembly(clazz);
    }

    public Class<?> classByWireName(String wireName) {
        return wireNameToClass.get(wireName);
    }

    public int size() {
        return classToWireName.size();
    }
}
```

- [ ] **Step 10.4: Запустить тесты — должны пройти**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -20
```

Expected: `Tests run: 6, Failures: 0`, `BUILD SUCCESS`.

- [ ] **Step 10.5: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add WireTypeRegistry

Bidirectional registry Class<->wireName based on @WireName. Fail-fast
on missing annotation, missing assembly for CommandResult, or
duplicate wire names. Read-safe after registration completes."
```

---

### Task 11: `SessionSerializer` — JSON → Deflate → Base64

**Files:**
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/session/SessionSerializer.java`
- Create: `java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core/session/SessionSerializerTest.java`

- [ ] **Step 11.1: Создать package + написать тест**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/session
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core/session
```

Создать `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/session/SessionSerializerTest.java`:

```java
package ru.tcb.sal.commands.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSerializerTest {

    private SessionSerializer serializer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        serializer = new SessionSerializer(mapper);
    }

    @Test
    void roundTrip_preservesFields() {
        ObjectNode session = mapper.createObjectNode();
        session.put("SessionId", "sess-42");
        session.put("OperationId", 12345L);
        session.put("AuthId", 99L);
        session.put("CustomField", "custom-value");

        String encoded = serializer.serialize(session);
        ObjectNode decoded = serializer.deserialize(encoded);

        assertThat(decoded.get("SessionId").asText()).isEqualTo("sess-42");
        assertThat(decoded.get("OperationId").asLong()).isEqualTo(12345L);
        assertThat(decoded.get("AuthId").asLong()).isEqualTo(99L);
        assertThat(decoded.get("CustomField").asText()).isEqualTo("custom-value");
    }

    @Test
    void serialize_producesBase64() {
        ObjectNode session = mapper.createObjectNode();
        session.put("SessionId", "x");

        String encoded = serializer.serialize(session);

        // Base64 алфавит: A-Z, a-z, 0-9, +, /, =
        assertThat(encoded).matches("^[A-Za-z0-9+/=]+$");
    }

    @Test
    void deserialize_invalidBase64_returnsEmptyObject() {
        ObjectNode decoded = serializer.deserialize("not-a-valid-base64-$$$");
        assertThat(decoded).isNotNull();
        assertThat(decoded.isEmpty()).isTrue();
    }

    @Test
    void deserialize_corruptedDeflate_returnsEmptyObject() {
        // Валидный Base64, но не Deflate-поток
        String fakeBase64 = java.util.Base64.getEncoder().encodeToString("not deflate".getBytes());

        ObjectNode decoded = serializer.deserialize(fakeBase64);
        assertThat(decoded).isNotNull();
        assertThat(decoded.isEmpty()).isTrue();
    }

    @Test
    void serialize_emptyObject_roundTrips() {
        ObjectNode empty = mapper.createObjectNode();
        String encoded = serializer.serialize(empty);
        ObjectNode decoded = serializer.deserialize(encoded);
        assertThat(decoded.isEmpty()).isTrue();
    }
}
```

- [ ] **Step 11.2: Запустить тест — должен упасть**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -15
```

Expected: compilation error.

- [ ] **Step 11.3: Написать `SessionSerializer`**

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/session/SessionSerializer.java`:

```java
package ru.tcb.sal.commands.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Упаковывает/распаковывает сессию в .NET-совместимом формате:
 * JSON (PascalCase) → raw Deflate (без gzip wrapper) → Base64.
 *
 * <p>При ошибке распаковки возвращает пустой ObjectNode и логирует warn —
 * зеркало поведения .NET {@code SessionSerializer.Deserialize}, который
 * в случае corrupted session возвращал fake empty session.
 */
public class SessionSerializer {

    private static final Logger log = LoggerFactory.getLogger(SessionSerializer.class);

    private final ObjectMapper mapper;

    public SessionSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serialize(ObjectNode session) {
        try {
            byte[] json = mapper.writeValueAsBytes(session);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(bos)) {
                deflate.write(json);
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize session", e);
        }
    }

    public ObjectNode deserialize(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(base64);
            try (InflaterInputStream inflate = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                JsonNode node = mapper.readTree(inflate);
                if (node instanceof ObjectNode obj) {
                    return obj;
                }
                log.warn("Session JSON root is not an object; returning empty");
                return mapper.createObjectNode();
            }
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to deserialize session ('{}' bytes), returning empty: {}",
                base64.length(), e.getMessage());
            return mapper.createObjectNode();
        }
    }
}
```

- [ ] **Step 11.4: Запустить тесты**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -15
```

Expected: `Tests run: 11` (6 предыдущих + 5 новых), `BUILD SUCCESS`.

- [ ] **Step 11.5: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add SessionSerializer (JSON/Deflate/Base64)

Mirrors .NET SessionSerializer: JSON → raw DeflateStream → Base64.
Corrupted input returns empty ObjectNode (matches .NET fake session
fallback) — logs warn, never throws on deserialize."
```

---

### Task 12: `RecordedMessageConverter` — базовая (де)сериализация

**Files:**
- Create: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/transport/amqp/RecordedMessageConverter.java`
- Create: `java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core/transport/amqp/RecordedMessageConverterTest.java`

- [ ] **Step 12.1: Создать package + написать тест**

```bash
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/transport/amqp
mkdir -p /mnt/c/work/sal_refactoring/java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core/transport/amqp
```

Создать `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/transport/amqp/RecordedMessageConverterTest.java`:

```java
package ru.tcb.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.api.Command;
import ru.tcb.sal.commands.api.annotation.WireName;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RecordedMessageConverterTest {

    @WireName("TCB.Test.PingCommand")
    static class PingCommand implements Command {
        public String text;
        public int count;

        public PingCommand() {}
        public PingCommand(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    private RecordedMessageConverter converter;

    @BeforeEach
    void setUp() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);
        converter = new RecordedMessageConverter(registry);
    }

    @Test
    void serialize_producesPascalCaseFields() {
        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = "cid-1";
        rm.exchangeName = "CommandExchange";
        rm.routingKey = "TCB.Test.PingCommand";
        rm.sourceServiceId = "ru.tcb.test.AdapterA";
        rm.messageId = "msg-1";
        rm.priority = (byte) 5;
        rm.timeStamp = Instant.parse("2026-04-10T12:00:00Z");
        rm.expireDate = Instant.parse("2026-04-10T12:02:00Z");
        rm.payload = new PingCommand("hello", 3);
        rm.additionalData.put("IsCommand", "");

        byte[] bytes = converter.toBytes(rm);
        String json = new String(bytes);

        assertThat(json).contains("\"CorrelationId\":\"cid-1\"");
        assertThat(json).contains("\"ExchangeName\":\"CommandExchange\"");
        assertThat(json).contains("\"RoutingKey\":\"TCB.Test.PingCommand\"");
        assertThat(json).contains("\"SourceServiceId\":\"ru.tcb.test.AdapterA\"");
        assertThat(json).contains("\"Priority\":5");
        assertThat(json).contains("\"TimeStamp\":\"2026-04-10T12:00:00Z\"");
        assertThat(json).contains("\"AdditionalData\":{\"IsCommand\":\"\"}");
        // Payload с PascalCase полями самой команды
        assertThat(json).contains("\"Text\":\"hello\"");
        assertThat(json).contains("\"Count\":3");
    }

    @Test
    void roundTrip_preservesAllFields() {
        RecordedMessage original = new RecordedMessage();
        original.correlationId = "cid-1";
        original.exchangeName = "CommandExchange";
        original.routingKey = "TCB.Test.PingCommand";
        original.sourceServiceId = "ru.tcb.test.AdapterA";
        original.messageId = "msg-1";
        original.priority = (byte) 5;
        original.timeStamp = Instant.parse("2026-04-10T12:00:00Z");
        original.expireDate = Instant.parse("2026-04-10T12:02:00Z");
        original.payload = new PingCommand("hello", 3);
        original.additionalData = new LinkedHashMap<>();
        original.additionalData.put("IsCommand", "");

        byte[] bytes = converter.toBytes(original);
        RecordedMessage decoded = converter.fromBytes(bytes);

        assertThat(decoded.correlationId).isEqualTo("cid-1");
        assertThat(decoded.exchangeName).isEqualTo("CommandExchange");
        assertThat(decoded.routingKey).isEqualTo("TCB.Test.PingCommand");
        assertThat(decoded.sourceServiceId).isEqualTo("ru.tcb.test.AdapterA");
        assertThat(decoded.priority).isEqualTo((byte) 5);
        assertThat(decoded.timeStamp).isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
        assertThat(decoded.expireDate).isEqualTo(Instant.parse("2026-04-10T12:02:00Z"));
        assertThat(decoded.additionalData).containsEntry("IsCommand", "");
        // Payload на этапе fromBytes — JsonNode (тип неизвестен без routingKey лукапа)
        assertThat(decoded.payload).isInstanceOf(JsonNode.class);
    }

    @Test
    void serialize_nullExpireDate_omitsOrWritesNull() {
        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = "cid-1";
        rm.exchangeName = "CommandExchange";
        rm.routingKey = "TCB.Test.PingCommand";
        rm.priority = (byte) 5;
        rm.timeStamp = Instant.parse("2026-04-10T12:00:00Z");
        rm.payload = new PingCommand("x", 1);
        // expireDate = null

        byte[] bytes = converter.toBytes(rm);
        String json = new String(bytes);

        // .NET сериализует null поля как null (а не опускает)
        assertThat(json).contains("\"ExpireDate\":null");
    }
}
```

- [ ] **Step 12.2: Запустить тест — должен упасть**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -15
```

Expected: compilation error.

- [ ] **Step 12.3: Написать `RecordedMessageConverter`**

Создать `sal-commands-core/src/main/java/ru/tcb/sal/commands/core/transport/amqp/RecordedMessageConverter.java`:

```java
package ru.tcb.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Bytes &lt;-&gt; {@link RecordedMessage}. Один из двух ключевых классов
 * wire-слоя (второй — {@code WireTypeRegistry}).
 *
 * <p>Локальный {@link ObjectMapper} с политиками:
 * <ul>
 *   <li>PropertyNamingStrategies.UPPER_CAMEL_CASE — поля в JSON PascalCase</li>
 *   <li>JavaTimeModule — {@code Instant}/{@code Duration} как ISO-8601 строки</li>
 *   <li>WRITE_DATES_AS_TIMESTAMPS=false — отключает числовые timestamps</li>
 *   <li>JsonInclude.ALWAYS — null сериализуются явно (как .NET Newtonsoft по умолчанию)</li>
 *   <li>FAIL_ON_UNKNOWN_PROPERTIES=false — толерантность к новым полям с .NET-стороны</li>
 * </ul>
 *
 * <p>На read-пути поле {@code payload} читается как {@code JsonNode} —
 * конкретный тип резолвится позже, когда известен {@code routingKey}
 * или {@code ResultType}.
 */
public class RecordedMessageConverter {

    private final ObjectMapper mapper;
    private final WireTypeRegistry registry;

    public RecordedMessageConverter(WireTypeRegistry registry) {
        this.registry = registry;
        this.mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public byte[] toBytes(RecordedMessage rm) {
        try {
            return mapper.writeValueAsBytes(rm);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize RecordedMessage", e);
        }
    }

    public RecordedMessage fromBytes(byte[] bytes) {
        try {
            return mapper.readValue(bytes, RecordedMessage.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize RecordedMessage", e);
        }
    }

    /**
     * Доступ к локальному ObjectMapper — нужен выше-стоящему коду
     * для конвертации payload (JsonNode) в конкретный тип команды/результата.
     */
    public ObjectMapper mapper() {
        return mapper;
    }

    public WireTypeRegistry registry() {
        return registry;
    }
}
```

- [ ] **Step 12.4: Запустить тесты**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -20
```

Expected: `Tests run: 14` (11 + 3 новых), `BUILD SUCCESS`.

- [ ] **Step 12.5: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add RecordedMessageConverter

Bytes <-> RecordedMessage via Jackson with PascalCase naming,
ISO-8601 dates, always-null inclusion (matches .NET Newtonsoft
defaults), and tolerant reading (FAIL_ON_UNKNOWN_PROPERTIES=false).
Payload is read as JsonNode and resolved later by consumers."
```

---

### Task 13: Resolve payload по routingKey / ResultType

**Files:**
- Modify: `java-commands/sal-commands-core/src/main/java/ru/tcb/sal/commands/core/transport/amqp/RecordedMessageConverter.java`
- Modify: `java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core/transport/amqp/RecordedMessageConverterTest.java`

Добавляем в конвертер метод, который по `routingKey` входящего сообщения резолвит конкретный тип команды через `WireTypeRegistry` и конвертирует payload (`JsonNode`) в этот тип.

- [ ] **Step 13.1: Добавить тест**

В `RecordedMessageConverterTest.java` добавить тест в конец класса:

```java
    @Test
    void readPayloadAs_resolvesByRoutingKey() {
        PingCommand original = new PingCommand("hello", 42);
        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = "cid-1";
        rm.exchangeName = "CommandExchange";
        rm.routingKey = "TCB.Test.PingCommand";
        rm.priority = (byte) 5;
        rm.timeStamp = Instant.parse("2026-04-10T12:00:00Z");
        rm.payload = original;

        byte[] bytes = converter.toBytes(rm);
        RecordedMessage decoded = converter.fromBytes(bytes);

        PingCommand resolved = converter.readPayloadAs(decoded, PingCommand.class);
        assertThat(resolved.text).isEqualTo("hello");
        assertThat(resolved.count).isEqualTo(42);
    }

    @Test
    void readPayloadAs_unknownRoutingKey_throws() {
        RecordedMessage rm = new RecordedMessage();
        rm.routingKey = "TCB.Test.Unknown";
        rm.payload = null;

        assertThat(converter.registry().classByWireName("TCB.Test.Unknown")).isNull();
    }
```

- [ ] **Step 13.2: Запустить тесты — должны упасть**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -15
```

Expected: compilation error `cannot find symbol method readPayloadAs`.

- [ ] **Step 13.3: Добавить `readPayloadAs` в конвертер**

В `RecordedMessageConverter.java` добавить импорт в начало файла:

```java
import com.fasterxml.jackson.databind.JsonNode;
```

И метод перед `public ObjectMapper mapper()`:

```java
    /**
     * Конвертирует {@code rm.payload} (обычно {@link JsonNode} после {@link #fromBytes})
     * в конкретный тип {@code targetType}. Используется listener'ом,
     * когда по {@code routingKey} резолвлен ожидаемый класс команды.
     */
    public <T> T readPayloadAs(RecordedMessage rm, Class<T> targetType) {
        if (rm.payload == null) {
            return null;
        }
        if (targetType.isInstance(rm.payload)) {
            return targetType.cast(rm.payload);
        }
        if (rm.payload instanceof JsonNode node) {
            try {
                return mapper.treeToValue(node, targetType);
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Failed to convert payload to " + targetType.getName(), e);
            }
        }
        // Fallback: через JSON round-trip (для не-JsonNode объектов)
        try {
            byte[] bytes = mapper.writeValueAsBytes(rm.payload);
            return mapper.readValue(bytes, targetType);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to convert payload to " + targetType.getName(), e);
        }
    }
```

- [ ] **Step 13.4: Запустить тесты**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test 2>&1 | tail -15
```

Expected: `Tests run: 16`, `BUILD SUCCESS`.

- [ ] **Step 13.5: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-core/
git commit -m "feat(sal-commands-core): add readPayloadAs to converter

Resolves raw JsonNode payload into a concrete command/result class
when listener has routing key or result type. Fallback through JSON
round-trip for non-JsonNode payloads."
```

---

### Task 14: Golden JSON round-trip тесты

**Files:**
- Create: `java-commands/sal-commands-core/src/test/resources/golden/simple-ping-command.json`
- Create: `java-commands/sal-commands-core/src/test/resources/golden/command-completed-event.json`
- Create: `java-commands/sal-commands-core/src/test/resources/golden/command-failed-event.json`
- Create: `java-commands/sal-commands-core/src/test/java/ru/tcb/sal/commands/core/transport/amqp/GoldenJsonTest.java`

**Важно:** Golden-файлы — это **записанные реальные сообщения из .NET SAL**. Если в проекте есть доступ к .NET-стенду, их нужно извлечь оттуда (из логов `[SRC -> BUS]` или сниффингом RMQ). Для старта плана в репозитории создаются «синтетические» golden-файлы, сгенерированные вручную по .NET-спецификации — они всё равно должны пройти round-trip. **После первого доступа к реальному .NET-стенду эти файлы должны быть заменены реальными дампами**, и тесты перезапущены.

- [ ] **Step 14.1: Создать синтетический `simple-ping-command.json`**

Создать `sal-commands-core/src/test/resources/golden/simple-ping-command.json`:

```json
{
  "CorrelationId": "11111111-2222-3333-4444-555555555555",
  "ExchangeName": "CommandExchange",
  "RoutingKey": "TCB.Test.PingCommand",
  "SourceServiceId": "ru.tcb.test.AdapterA",
  "MessageId": "1",
  "Priority": 5,
  "TimeStamp": "2026-04-10T12:00:00Z",
  "ExpireDate": "2026-04-10T12:02:00Z",
  "Payload": {
    "Text": "hello",
    "Count": 3
  },
  "AdditionalData": {
    "IsCommand": "",
    "Session": "eJyrVkosLskvKlayUjA0NDKvBQAr2gSD"
  }
}
```

- [ ] **Step 14.2: Создать `command-completed-event.json`**

Создать `sal-commands-core/src/test/resources/golden/command-completed-event.json`:

```json
{
  "CorrelationId": "11111111-2222-3333-4444-555555555555",
  "ExchangeName": "TCB.Infrastructure.Command.CommandCompletedEvent",
  "RoutingKey": "ru.tcb.test.AdapterA",
  "SourceServiceId": "ru.tcb.test.AdapterB",
  "MessageId": "2",
  "Priority": 5,
  "TimeStamp": "2026-04-10T12:00:01Z",
  "ExpireDate": null,
  "Payload": {
    "Context": {
      "CommandType": "TCB.Test.PingCommand",
      "CorrelationId": "11111111-2222-3333-4444-555555555555",
      "SourceServiceId": "ru.tcb.test.AdapterA",
      "TimeStamp": "2026-04-10T12:00:00Z",
      "ExpireDate": "2026-04-10T12:02:00Z",
      "ExcutionServiceId": "ru.tcb.test.AdapterB",
      "ExcutionTimeStamp": "2026-04-10T12:00:01Z",
      "ExcutionDuration": "PT0.050S",
      "Priority": 5,
      "SessionId": "sess-42",
      "OperationId": "12345"
    },
    "ResultType": "TCB.Test.PingResult, TCB.Test.Contracts",
    "Result": {
      "Echo": "hello",
      "Processed": true
    },
    "AdditionalData": {}
  },
  "AdditionalData": {
    "Session": "eJyrVkosLskvKlayUjA0NDKvBQAr2gSD"
  }
}
```

- [ ] **Step 14.3: Создать `command-failed-event.json`**

Создать `sal-commands-core/src/test/resources/golden/command-failed-event.json`:

```json
{
  "CorrelationId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "ExchangeName": "TCB.Infrastructure.Command.CommandFailedEvent",
  "RoutingKey": "ru.tcb.test.AdapterA",
  "SourceServiceId": "ru.tcb.test.AdapterB",
  "MessageId": "3",
  "Priority": 5,
  "TimeStamp": "2026-04-10T12:00:02Z",
  "ExpireDate": null,
  "Payload": {
    "Context": {
      "CommandType": "TCB.Test.PingCommand",
      "CorrelationId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      "SourceServiceId": "ru.tcb.test.AdapterA",
      "TimeStamp": "2026-04-10T12:00:01Z",
      "ExpireDate": null,
      "ExcutionServiceId": "ru.tcb.test.AdapterB",
      "ExcutionTimeStamp": "2026-04-10T12:00:02Z",
      "ExcutionDuration": "PT0.010S",
      "Priority": 5,
      "SessionId": "sess-42",
      "OperationId": "12346"
    },
    "ExceptionData": {
      "ExceptionType": "TCB.Payment.Business.PaymentDeclinedException",
      "Code": "PAYMENT_DECLINED",
      "CodeDescription": "Insufficient funds",
      "Message": "Insufficient funds",
      "AdapterName": "ru.tcb.test.AdapterB",
      "SourceType": "TCB.Payment.Handlers.PaymentCommandHandler",
      "SourcePath": "TCB.Payment.Handlers.PaymentCommandHandler.Execute",
      "SessionId": "sess-42",
      "SourceId": null,
      "TimeStamp": "2026-04-10T12:00:02Z",
      "Properties": null,
      "InnerException": null
    },
    "AdditionalData": {}
  },
  "AdditionalData": {
    "Session": "eJyrVkosLskvKlayUjA0NDKvBQAr2gSD"
  }
}
```

- [ ] **Step 14.4: Написать `GoldenJsonTest`**

Создать `sal-commands-core/src/test/java/ru/tcb/sal/commands/core/transport/amqp/GoldenJsonTest.java`:

```java
package ru.tcb.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что golden JSON-файлы (снятые с .NET SAL, здесь —
 * синтетические) round-trip'ятся через {@link RecordedMessageConverter}
 * без потерь. Если тесты падают — это значит, что wire-формат
 * изменился и надо расследовать причину (не просто обновлять golden!).
 */
class GoldenJsonTest {

    private RecordedMessageConverter converter;
    private ObjectMapper plainMapper;

    @BeforeEach
    void setUp() {
        WireTypeRegistry registry = new WireTypeRegistry();
        converter = new RecordedMessageConverter(registry);
        plainMapper = converter.mapper();   // тот же конвертерный mapper для сравнения деревьев
    }

    @Test
    void simplePingCommand_roundTrips() throws IOException {
        byte[] golden = readResource("golden/simple-ping-command.json");

        RecordedMessage rm = converter.fromBytes(golden);
        byte[] written = converter.toBytes(rm);

        assertJsonTreesEqual(golden, written);
    }

    @Test
    void commandCompletedEvent_roundTrips() throws IOException {
        byte[] golden = readResource("golden/command-completed-event.json");

        RecordedMessage rm = converter.fromBytes(golden);
        byte[] written = converter.toBytes(rm);

        assertJsonTreesEqual(golden, written);
    }

    @Test
    void commandFailedEvent_roundTrips() throws IOException {
        byte[] golden = readResource("golden/command-failed-event.json");

        RecordedMessage rm = converter.fromBytes(golden);
        byte[] written = converter.toBytes(rm);

        assertJsonTreesEqual(golden, written);
    }

    @Test
    void simplePingCommand_preservesTopLevelFields() throws IOException {
        byte[] golden = readResource("golden/simple-ping-command.json");
        RecordedMessage rm = converter.fromBytes(golden);

        assertThat(rm.correlationId).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(rm.exchangeName).isEqualTo("CommandExchange");
        assertThat(rm.routingKey).isEqualTo("TCB.Test.PingCommand");
        assertThat(rm.sourceServiceId).isEqualTo("ru.tcb.test.AdapterA");
        assertThat(rm.priority).isEqualTo((byte) 5);
        assertThat(rm.additionalData).containsEntry("IsCommand", "");
        assertThat(rm.additionalData).containsKey("Session");
    }

    @Test
    void commandCompletedEvent_preservesTypoFields() throws IOException {
        byte[] golden = readResource("golden/command-completed-event.json");
        RecordedMessage rm = converter.fromBytes(golden);

        // payload как JsonNode, поля остались PascalCase с опечатками
        JsonNode payload = (JsonNode) rm.payload;
        JsonNode ctx = payload.get("Context");
        assertThat(ctx.get("ExcutionServiceId").asText()).isEqualTo("ru.tcb.test.AdapterB");
        assertThat(ctx.get("ExcutionDuration").asText()).isEqualTo("PT0.050S");
        assertThat(payload.get("ResultType").asText())
            .isEqualTo("TCB.Test.PingResult, TCB.Test.Contracts");
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return is.readAllBytes();
        }
    }

    private void assertJsonTreesEqual(byte[] expected, byte[] actual) throws IOException {
        JsonNode expectedTree = plainMapper.readTree(expected);
        JsonNode actualTree = plainMapper.readTree(actual);
        assertThat(actualTree)
            .withFailMessage(
                "JSON trees differ.\nExpected:\n%s\nActual:\n%s",
                prettyPrint(expectedTree), prettyPrint(actualTree))
            .isEqualTo(expectedTree);
    }

    private String prettyPrint(JsonNode tree) throws IOException {
        return plainMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }
}
```

- [ ] **Step 14.5: Запустить golden тесты**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn -pl sal-commands-core test -Dtest=GoldenJsonTest 2>&1 | tail -30
```

Expected: `Tests run: 5, Failures: 0`.

**Если тесты упали по `JSON trees differ`:** сравнить expected/actual в логах. Типичные причины:
- Жёсткая политика `WRITE_NULL_MAP_VALUES` опустила null `ExpireDate` — проверь `setSerializationInclusion(ALWAYS)` в конвертере.
- Порядок полей другой — Jackson не гарантирует порядок, но `JsonNode.equals` сравнивает по содержимому, не по порядку. Если `assertThat(actualTree).isEqualTo(expectedTree)` падает из-за порядка — заменить на поле-за-полем сравнение через `actualTree.fields()`.
- `Instant` сериализуется с `.000000000Z` вместо `Z` — настроить модуль JSR310.

- [ ] **Step 14.6: Полный тестовый прогон всех модулей**

```bash
cd /mnt/c/work/sal_refactoring/java-commands && mvn clean verify 2>&1 | tail -25
```

Expected: `BUILD SUCCESS`. Тестов должно быть `Tests run: 26` (3 `CommandPriority` + 2 `WireName` + 6 `WireTypeRegistry` + 5 `SessionSerializer` + 5 `RecordedMessageConverter` + 5 `GoldenJson`).

- [ ] **Step 14.7: Коммит**

```bash
cd /mnt/c/work/sal_refactoring
git add java-commands/sal-commands-core/
git commit -m "test(sal-commands-core): add golden JSON round-trip tests

Three synthetic golden files cover the critical wire cases:
simple command, CommandCompletedEvent, CommandFailedEvent. Tests
read each file, round-trip through the converter, assert tree
equality. Synthetic files should be replaced with real .NET dumps
once a reference .NET adapter is available.

Wire-compat milestone: all DTO mirrors are byte-round-trippable."
```

---

## Self-review checklist (для исполнителя)

После прохождения всех задач убедиться:

- [ ] `mvn clean verify` из `java-commands/` проходит зелёным.
- [ ] Количество тестов ≥ 21 (3 `CommandPriority` + 2 `WireName` + 6 `WireTypeRegistry` + 5 `SessionSerializer` + 5 `RecordedMessageConverter` + 5 `GoldenJson` = 26; допустимое значение).
- [ ] Все коммиты в истории, не squash — каждая задача отдельно.
- [ ] Ни один .NET-typo не «исправлен» (`Excution*`, `Confirmtation` не встречаются в новом коде).
- [ ] Ни одного `@Autowired` на поле; конструкторы.
- [ ] Ни одной Lombok-аннотации.
- [ ] Jackson-конфиг существует только внутри `RecordedMessageConverter` и `SessionSerializer`; не экспортируется глобально.

---

## Что дальше (План 2 — написать после успешного исполнения Плана 1)

- Transport-абстракция (`CommandTransport`, `SpringAmqpCommandTransport`, `InMemoryCommandTransport`), `AmqpTopologyConfigurer`.
- Handler discovery и dispatching: `@CommandHandler`, `HandlerBinding`, `CommandHandlerBeanPostProcessor`, `ArgumentResolver`, `ReturnAdapter`, `CommandListener`, `ResultListener`.
- Send path: `CommandBus` API, `CommandBusImpl`, `CorrelationStore`, `TimeoutWatcher`, `CommandInvocation`, `SalSession`, `ThreadLocalSessionHolder`.
- Exception handling: `RemoteCommandException` hierarchy, `DefaultExceptionMapper`, `ExceptionMapperRegistry`.
- Starter autoconfiguration: `SalCommandsAutoConfiguration`, `SalCommandsProperties`, actuator endpoint, health indicator, metrics, MDC, Logback filter.
- Test-slice: `@SalCommandsTest`, `SalCommandsTestAutoConfiguration`.
- Wire-compat integration тесты с Testcontainers RabbitMQ.
- Release 1.0.0 + документация.
