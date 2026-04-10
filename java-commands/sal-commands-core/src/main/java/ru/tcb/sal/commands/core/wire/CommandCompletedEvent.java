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
