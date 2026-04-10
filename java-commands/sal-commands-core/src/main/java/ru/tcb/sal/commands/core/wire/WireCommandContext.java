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
