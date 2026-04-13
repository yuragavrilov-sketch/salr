package ru.tcb.sal.demo;

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

    @Value("${demo.adapter-name}")
    private String adapterName;

    @Bean
    public WireTypeRegistry wireTypeRegistry() {
        return new WireTypeRegistry();
    }

    @Bean
    public RecordedMessageConverter recordedMessageConverter(WireTypeRegistry registry) {
        return new RecordedMessageConverter(registry);
    }

    @Bean
    public SessionSerializer sessionSerializer() {
        return new SessionSerializer();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        return new RabbitTemplate(connectionFactory);
    }

    @Bean
    public Queue demoResultQueue() {
        return QueueBuilder.durable("cmd." + adapterName).build();
    }

    @Bean
    public Declarables resultBindings() {
        Queue queue = demoResultQueue();
        DirectExchange completedExchange = new DirectExchange("TCB.Infrastructure.Command.CommandCompletedEvent", true, false);
        DirectExchange failedExchange = new DirectExchange("TCB.Infrastructure.Command.CommandFailedEvent", true, false);
        DirectExchange commandExchange = new DirectExchange("CommandExchange", true, false);

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
