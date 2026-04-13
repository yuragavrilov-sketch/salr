package ru.tcb.sal.commands.core.wire;

/**
 * Mirror of .NET TCB.Infrastructure.Exceptions.InfrastructureExceptionDTO.
 * Recursive structure: innerException is the same type.
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
    public String timeStamp;
    public Object properties;
    public InfrastructureExceptionDto innerException;
}
