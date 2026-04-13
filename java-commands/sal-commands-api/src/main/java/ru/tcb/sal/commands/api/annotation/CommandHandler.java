package ru.tcb.sal.commands.api.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommandHandler {
    String value() default "";
    ErrorPolicy onError() default ErrorPolicy.REPLY_WITH_FAILURE;
}
