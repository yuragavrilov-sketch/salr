package ru.tcb.sal.commands.api;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Команда с типизированным результатом.
 * Соответствует .NET TCB.Infrastructure.Command.IHaveResult&lt;TResult&gt;.
 *
 * @param <R> тип результата
 */
public interface CommandWithResult<R extends CommandResult> extends Command {

    /**
     * Возвращает runtime Class результата.
     * По умолчанию резолвит через рефлексию по generic-параметру первого
     * подходящего интерфейса {@code CommandWithResult&lt;R&gt;} в иерархии класса.
     * Override для динамических случаев или когда тип результата нельзя
     * вывести из generic-параметра (raw types, дженерик-вилки).
     */
    @SuppressWarnings("unchecked")
    default Class<R> resultType() {
        Class<?> current = getClass();
        while (current != null && current != Object.class) {
            for (Type generic : current.getGenericInterfaces()) {
                if (generic instanceof ParameterizedType pt
                        && pt.getRawType() instanceof Class<?> raw
                        && CommandWithResult.class.isAssignableFrom(raw)) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length > 0 && args[0] instanceof Class<?> resolved) {
                        return (Class<R>) resolved;
                    }
                }
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException(
            "Cannot resolve result type of " + getClass().getName()
                + "; override resultType() explicitly");
    }
}
