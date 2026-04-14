package ru.copperside.sal.commands.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.SmartInitializingSingleton;
import ru.copperside.sal.commands.core.handler.CommandHandlerRegistry;
import ru.copperside.sal.commands.core.listener.CommandListener;
import ru.copperside.sal.commands.core.transport.amqp.RecordedMessageConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs after all singleton beans are initialized: collects every @CommandHandler
 * registered in {@link CommandHandlerRegistry}, declares the matching
 * {@code Command_<FullName>} queue + binding, and attaches them to the dispatch
 * container so incoming commands start flowing.
 *
 * <p>Using {@link SmartInitializingSingleton} guarantees handler registrations
 * (which happen in {@link ru.copperside.sal.commands.core.handler.CommandHandlerBeanPostProcessor})
 * are complete before queues are declared.
 */
public class SalCommandDispatchBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SalCommandDispatchBootstrap.class);

    private final SimpleMessageListenerContainer container;
    private final CommandHandlerRegistry handlerRegistry;
    private final CommandListener commandListener;
    private final RecordedMessageConverter converter;
    private final AmqpAdmin amqpAdmin;

    public SalCommandDispatchBootstrap(SimpleMessageListenerContainer container,
                                        CommandHandlerRegistry handlerRegistry,
                                        CommandListener commandListener,
                                        RecordedMessageConverter converter,
                                        AmqpAdmin amqpAdmin) {
        this.container = container;
        this.handlerRegistry = handlerRegistry;
        this.commandListener = commandListener;
        this.converter = converter;
        this.amqpAdmin = amqpAdmin;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (handlerRegistry.size() == 0) {
            log.info("[SAL] No @CommandHandler beans found — dispatch container stays idle");
            return;
        }

        DirectExchange commandExchange = new DirectExchange("CommandExchange", true, false);
        amqpAdmin.declareExchange(commandExchange);

        List<String> queueNames = new ArrayList<>();
        for (var binding : handlerRegistry.all()) {
            String queueName = "Command_" + binding.wireName();
            Queue q = QueueBuilder.durable(queueName).build();
            amqpAdmin.declareQueue(q);
            amqpAdmin.declareBinding(BindingBuilder.bind(q).to(commandExchange).with(binding.wireName()));
            queueNames.add(queueName);
            log.info("[SAL] Auto-declared handler queue: '{}' -> '{}'", queueName, binding.wireName());
        }

        container.setQueueNames(queueNames.toArray(new String[0]));
        container.setMessageListener(message -> {
            var amqpMsg = converter.fromSpringMessage(message);
            commandListener.onMessage(amqpMsg);
        });
        container.start();
        log.info("[SAL] Command dispatch container started on {} queues", queueNames.size());
    }
}
