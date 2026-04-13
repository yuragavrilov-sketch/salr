package ru.tcb.sal.commands.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface CommandBus {
    <R extends CommandResult> R execute(CommandWithResult<R> command);
    <R extends CommandResult> CompletableFuture<R> executeAsync(CommandWithResult<R> command);
    <R extends CommandResult> CompletableFuture<R> executeAsync(
        CommandWithResult<R> command, Duration timeout, CommandPriority priority);
    String publish(Command command);
}
