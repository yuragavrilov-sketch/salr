package ru.tcb.sal.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
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

@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    @Value("${demo.adapter-name}")
    private String adapterName;

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
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        return template;
    }

    @Bean
    public Queue demoResultQueue() {
        String queueName = "cmd." + adapterName;
        log.info("[CONFIG] Declaring durable queue: '{}'", queueName);
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Declarables resultBindings() {
        Queue queue = demoResultQueue();
        DirectExchange completedExchange = new DirectExchange("TCB.Infrastructure.Command.CommandCompletedEvent", true, false);
        DirectExchange failedExchange = new DirectExchange("TCB.Infrastructure.Command.CommandFailedEvent", true, false);
        DirectExchange commandExchange = new DirectExchange("CommandExchange", true, false);

        log.info("[CONFIG] Setting up bindings:");
        log.info("[CONFIG]   queue='{}' -> CommandCompletedEvent with key='{}'", queue.getName(), adapterName);
        log.info("[CONFIG]   queue='{}' -> CommandFailedEvent with key='{}'", queue.getName(), adapterName);
        log.info("[CONFIG]   Declaring exchanges: CommandExchange, CommandCompletedEvent, CommandFailedEvent");

        return new Declarables(
            completedExchange,
            failedExchange,
            commandExchange,
            queue,
            BindingBuilder.bind(queue).to(completedExchange).with(adapterName),
            BindingBuilder.bind(queue).to(failedExchange).with(adapterName)
        );
    }
}
