package ru.tcb.sal.commands.core.wire;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirror of .NET TCB.Infrastructure.Command.CommandCompletedEvent.
 * Lives in the AMQP body for result messages.
 */
public class CommandCompletedEvent {
    public String resultType;
    public Object result;
    public Map<String, String> additionalData = new LinkedHashMap<>();
    public WireCommandContext context;
}
