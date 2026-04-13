package ru.tcb.sal.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo-specific RabbitMQ config. Adds per-command queue bindings for
 * incoming command handling (on top of starter's autoconfig which
 * already handles result bindings and core beans).
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    @Value("${demo.handle-commands:}")
    private String handleCommands;

    @Value("${demo.per-command-queues:true}")
    private boolean perCommandQueues;

    @Value("${demo.adapter-name:JavaDemoAdapter}")
    private String adapterName;

    @Bean
    public Declarables demoCommandBindings() {
        DirectExchange commandExchange = new DirectExchange("CommandExchange", true, false);
        List<Declarable> declarations = new ArrayList<>();
        declarations.add(commandExchange);

        if (handleCommands != null && !handleCommands.isBlank()) {
            for (String cmdType : handleCommands.split(",")) {
                String trimmed = cmdType.trim();
                if (trimmed.isEmpty()) continue;

                if (perCommandQueues) {
                    String queueName = "Command_" + trimmed;
                    Queue queue = QueueBuilder.durable(queueName).build();
                    declarations.add(queue);
                    declarations.add(BindingBuilder.bind(queue).to(commandExchange).with(trimmed));
                    log.info("[DEMO] Command handler: queue='{}' -> CommandExchange key='{}'",
                        queueName, trimmed);
                }
            }
        }
        return new Declarables(declarations);
    }

    @Bean
    public SimpleMessageListenerContainer demoCommandListenerContainer(
            ConnectionFactory connectionFactory,
            IncomingCommandHandler commandHandler) {
        if (handleCommands == null || handleCommands.isBlank()) {
            SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(connectionFactory);
            c.setAutoStartup(false);
            return c;
        }

        List<String> queueNames = new ArrayList<>();
        for (String cmdType : handleCommands.split(",")) {
            String trimmed = cmdType.trim();
            if (trimmed.isEmpty()) continue;
            if (perCommandQueues) {
                queueNames.add("Command_" + trimmed);
            } else {
                queueNames.add("cmd." + adapterName);
                break;
            }
        }

        log.info("[DEMO] Command listener for queues: {}", queueNames);
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueNames.toArray(new String[0]));
        container.setMessageListener(commandHandler::handleCommand);
        container.setConcurrentConsumers(1);
        return container;
    }

    /**
     * Override starter's result listener container — demo handles results
     * via its own @RabbitListener in ResultListenerService.
     */
    @Bean(name = "salResultListenerContainer")
    public SimpleMessageListenerContainer salResultListenerContainer(ConnectionFactory cf) {
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(cf);
        c.setAutoStartup(false);
        return c;
    }
}
