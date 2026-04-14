package ru.copperside.sal.commands.core.wire;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirror of .NET TCB.Infrastructure.Command.CommandFailedEvent.
 * Lives in the AMQP body for failure messages.
 */
public class CommandFailedEvent {
    public InfrastructureExceptionDto exceptionData;
    public Map<String, String> additionalData = new LinkedHashMap<>();
    public WireCommandContext context;
}
