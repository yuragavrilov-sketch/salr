package ru.copperside.sal.commands.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Привязывает Java-класс к .NET wire-имени.
 *
 * <p>Для команд — routing key и FullName; для результатов — также
 * AssemblyQualifiedName (комбинация value + assembly через запятую).
 *
 * <p>Без этой аннотации Java-классы, реализующие Command/CommandResult,
 * не могут быть зарегистрированы в {@code WireTypeRegistry}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WireName {

    /** Полное wire-имя (.NET FullName), напр. "TCB.Payment.Request.CreatePaymentCommand". */
    String value();

    /**
     * Имя .NET сборки (только для классов результата, обязательно).
     * Напр. "TCB.Payment.Contracts". Для команд — пустая строка.
     */
    String assembly() default "";
}
