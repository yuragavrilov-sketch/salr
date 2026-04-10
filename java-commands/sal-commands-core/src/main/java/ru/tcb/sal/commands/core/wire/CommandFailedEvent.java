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
    public Map<String, String> additionalData = new LinkedHashMap<>();
}
