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
