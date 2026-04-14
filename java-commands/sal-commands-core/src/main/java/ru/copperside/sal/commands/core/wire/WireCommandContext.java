package ru.copperside.sal.commands.core.wire;

/**
 * Mirror of .NET TCB.Infrastructure.Command.CommandContext.
 * ALL fields are String to preserve exact .NET serialization format:
 * Priority is enum name ("Normal"), Duration is .NET TimeSpan ("00:00:00.123"),
 * timestamps have inconsistent timezone handling.
 *
 * Parsing to typed values happens in the mapping to api/CommandContext, not here.
 */
public class WireCommandContext {
    public String commandType;
    public String correlationId;
    public String sourceServiceId;
    public String timeStamp;
    public String expireDate;
    public String priority;
    public String excutionServiceId;
    public String excutionTimeStamp;
    public String excutionDuration;
    public String sessionId;
    public String operationId;
}
