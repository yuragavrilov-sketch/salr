package ru.copperside.sal.commands.core.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import ru.copperside.sal.commands.api.CommandContext;
import ru.copperside.sal.commands.api.annotation.CommandHandler;
import ru.copperside.sal.commands.api.annotation.WireName;
import ru.copperside.sal.commands.core.wire.WireTypeRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

public class CommandHandlerBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandHandlerBeanPostProcessor.class);
    private final CommandHandlerRegistry registry;
    private final WireTypeRegistry wireRegistry;

    public CommandHandlerBeanPostProcessor(CommandHandlerRegistry registry, WireTypeRegistry wireRegistry) {
        this.registry = registry;
        this.wireRegistry = wireRegistry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = AopUtils.getTargetClass(bean);
        for (Method method : clazz.getDeclaredMethods()) {
            CommandHandler ann = AnnotationUtils.findAnnotation(method, CommandHandler.class);
            if (ann == null) continue;
            validateAndRegister(bean, method, ann);
        }
        return bean;
    }

    private void validateAndRegister(Object bean, Method method, CommandHandler ann) {
        if (method.getParameterCount() == 0) {
            throw new IllegalStateException(
                "@CommandHandler must have at least one parameter (the command): " + method);
        }

        Class<?> commandClass = method.getParameterTypes()[0];
        String wireName = ann.value();
        if (wireName.isEmpty()) {
            WireName wn = commandClass.getAnnotation(WireName.class);
            if (wn == null) {
                throw new IllegalStateException(
                    "First parameter of @CommandHandler must have @WireName: " + method);
            }
            wireName = wn.value();
        }

        boolean hasContext = method.getParameterCount() >= 2
            && CommandContext.class.isAssignableFrom(method.getParameterTypes()[1]);
        boolean isVoid = method.getReturnType() == void.class || method.getReturnType() == Void.class;
        boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());
        Class<?> resultClass = isVoid ? null : method.getReturnType();

        try {
            method.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(bean);

            // Auto-register command/result types in WireTypeRegistry if not already there.
            // This saves users from a separate @Bean WireTypeRegistry wiring step —
            // the @WireName on command/result is enough.
            if (commandClass.isAnnotationPresent(WireName.class)
                    && !wireRegistry.isRegistered(commandClass)) {
                wireRegistry.register(commandClass);
            }
            if (resultClass != null && resultClass.isAnnotationPresent(WireName.class)
                    && !wireRegistry.isRegistered(resultClass)) {
                wireRegistry.register(resultClass);
            }

            registry.register(new HandlerBinding(
                wireName, commandClass, resultClass, bean, handle,
                hasContext, isVoid, isAsync, ann.onError()));

            log.info("[BPP] Found @CommandHandler: {}.{}() -> '{}'",
                bean.getClass().getSimpleName(), method.getName(), wireName);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access @CommandHandler method: " + method, e);
        }
    }
}
