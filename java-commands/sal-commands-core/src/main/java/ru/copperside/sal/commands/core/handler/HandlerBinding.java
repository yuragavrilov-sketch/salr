package ru.copperside.sal.commands.core.handler;

import ru.copperside.sal.commands.api.annotation.ErrorPolicy;
import java.lang.invoke.MethodHandle;

public record HandlerBinding(
    String wireName,
    Class<?> commandClass,
    Class<?> resultClass,
    Object bean,
    MethodHandle invoker,
    boolean hasContextParam,
    boolean isVoid,
    boolean isAsync,
    ErrorPolicy errorPolicy
) {}
