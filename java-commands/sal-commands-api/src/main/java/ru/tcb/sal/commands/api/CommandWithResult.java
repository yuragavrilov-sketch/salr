package ru.tcb.sal.commands.api;

import org.springframework.core.GenericTypeResolver;

/**
 * Команда с типизированным результатом.
 * Соответствует .NET TCB.Infrastructure.Command.IHaveResult&lt;TResult&gt;.
 *
 * @param <R> тип результата
 */
public interface CommandWithResult<R extends CommandResult> extends Command {

    /**
     * Возвращает runtime Class результата.
     * По умолчанию резолвит через GenericTypeResolver; override для
     * динамических случаев.
     */
    @SuppressWarnings("unchecked")
    default Class<R> resultType() {
        Class<?> resolved = GenericTypeResolver.resolveTypeArgument(
            getClass(), CommandWithResult.class);
        if (resolved == null) {
            throw new IllegalStateException(
                "Cannot resolve result type of " + getClass().getName()
                    + "; override resultType() explicitly");
        }
        return (Class<R>) resolved;
    }
}
