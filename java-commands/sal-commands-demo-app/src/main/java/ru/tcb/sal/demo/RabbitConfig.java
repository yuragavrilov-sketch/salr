package ru.tcb.sal.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    @Value("${demo.adapter-name}")
    private String adapterName;

    @Value("${demo.handle-commands:}")
    private String handleCommands;

    @Value("${demo.per-command-queues:true}")
    private boolean perCommandQueues;

    @Bean
    public WireTypeRegistry wireTypeRegistry() {
        log.info("[CONFIG] Creating WireTypeRegistry");
        return new WireTypeRegistry();
    }

    @Bean
    public RecordedMessageConverter recordedMessageConverter(WireTypeRegistry registry) {
        log.info("[CONFIG] Creating RecordedMessageConverter");
        return new RecordedMessageConverter(registry);
    }

    @Bean
    public SessionSerializer sessionSerializer() {
        log.info("[CONFIG] Creating SessionSerializer");
        return new SessionSerializer();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        log.info("[CONFIG] Creating RabbitTemplate");
        return new RabbitTemplate(connectionFactory);
    }

    @Bean
    public Queue demoResultQueue() {
        String queueName = "cmd." + adapterName;
        log.info("[CONFIG] Declaring durable queue: '{}'", queueName);
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Declarables rabbitBindings() {
        Queue queue = demoResultQueue();
        DirectExchange completedExchange = new DirectExchange("TCB.Infrastructure.Command.CommandCompletedEvent", true, false);
        DirectExchange failedExchange = new DirectExchange("TCB.Infrastructure.Command.CommandFailedEvent", true, false);
        DirectExchange commandExchange = new DirectExchange("CommandExchange", true, false);

        List<Declarable> declarations = new ArrayList<>();
        declarations.add(completedExchange);
        declarations.add(failedExchange);
        declarations.add(commandExchange);
        declarations.add(queue);

        // Result bindings — receive completed/failed results by our adapter name
        declarations.add(BindingBuilder.bind(queue).to(completedExchange).with(adapterName));
        declarations.add(BindingBuilder.bind(queue).to(failedExchange).with(adapterName));
        log.info("[CONFIG] Result bindings: queue='{}' -> Completed/Failed with key='{}'",
            queue.getName(), adapterName);

        // Command handler bindings — receive incoming commands by command type
        if (handleCommands != null && !handleCommands.isBlank()) {
            for (String cmdType : handleCommands.split(",")) {
                String trimmed = cmdType.trim();
                if (trimmed.isEmpty()) continue;

                if (perCommandQueues) {
                    // .NET SAL convention: one queue per command type = "Command_<FullName>"
                    String cmdQueueName = "Command_" + trimmed;
                    Queue cmdQueue = QueueBuilder.durable(cmdQueueName).build();
                    declarations.add(cmdQueue);
                    declarations.add(BindingBuilder.bind(cmdQueue).to(commandExchange).with(trimmed));
                    log.info("[CONFIG] Command handler: queue='{}' -> CommandExchange key='{}'",
                        cmdQueueName, trimmed);
                } else {
                    // Single shared queue for all commands
                    declarations.add(BindingBuilder.bind(queue).to(commandExchange).with(trimmed));
                    log.info("[CONFIG] Command handler: queue='{}' -> CommandExchange key='{}'",
                        queue.getName(), trimmed);
                }
            }
        }

        return new Declarables(declarations);
    }

    @Bean
    public SimpleMessageListenerContainer commandListenerContainer(
            ConnectionFactory connectionFactory,
            IncomingCommandHandler commandHandler) {
        if (handleCommands == null || handleCommands.isBlank()) {
            // No commands to handle — return empty container
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
            container.setAutoStartup(false);
            return container;
        }

        List<String> queueNames = new ArrayList<>();
        for (String cmdType : handleCommands.split(",")) {
            String trimmed = cmdType.trim();
            if (trimmed.isEmpty()) continue;
            if (perCommandQueues) {
                queueNames.add("Command_" + trimmed);
            } else {
                queueNames.add("cmd." + adapterName);
                break; // single queue
            }
        }

        log.info("[CONFIG] Creating command listener for queues: {}", queueNames);

        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueNames.toArray(new String[0]));
        container.setMessageListener(commandHandler::handleCommand);
        container.setConcurrentConsumers(1);
        container.setAutoStartup(true);
        return container;
    }
}
