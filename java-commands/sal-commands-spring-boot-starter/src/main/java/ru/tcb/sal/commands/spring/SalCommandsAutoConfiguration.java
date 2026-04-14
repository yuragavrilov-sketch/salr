package ru.tcb.sal.commands.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.tcb.sal.commands.api.CommandBus;
import ru.tcb.sal.commands.core.bus.CommandBusImpl;
import ru.tcb.sal.commands.core.bus.CorrelationStore;
import ru.tcb.sal.commands.core.bus.TimeoutWatcher;
import ru.tcb.sal.commands.core.exception.ExceptionMapper;
import ru.tcb.sal.commands.core.handler.CommandHandlerBeanPostProcessor;
import ru.tcb.sal.commands.core.handler.CommandHandlerRegistry;
import ru.tcb.sal.commands.core.listener.CommandListener;
import ru.tcb.sal.commands.core.listener.ResponseBuilder;
import ru.tcb.sal.commands.core.listener.ResultListener;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.CommandTransport;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.transport.amqp.SpringAmqpCommandTransport;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(prefix = "sal.command-bus", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SalCommandsProperties.class)
public class SalCommandsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SalCommandsAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public WireTypeRegistry salWireTypeRegistry() {
        return new WireTypeRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public RecordedMessageConverter salRecordedMessageConverter(WireTypeRegistry registry) {
        return new RecordedMessageConverter(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionSerializer salSessionSerializer() {
        return new SessionSerializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExceptionMapper salExceptionMapper() {
        return new ExceptionMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationStore salCorrelationStore() {
        return new CorrelationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeoutWatcher salTimeoutWatcher(CorrelationStore store) {
        TimeoutWatcher watcher = new TimeoutWatcher(store);
        watcher.start();
        return watcher;
    }

    @Bean
    @ConditionalOnMissingBean(CommandTransport.class)
    public SpringAmqpCommandTransport salCommandTransport(RabbitTemplate rabbitTemplate) {
        return new SpringAmqpCommandTransport(rabbitTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandBus salCommandBus(CommandTransport transport,
                                     RecordedMessageConverter converter,
                                     SessionSerializer sessionSerializer,
                                     CorrelationStore store,
                                     SalCommandsProperties props) {
        log.info("[SAL] CommandBus created: adapter='{}' timeout={}",
            props.adapterName(), props.defaultTimeout());
        return new CommandBusImpl(transport, converter, sessionSerializer, store,
            props.adapterName(), props.defaultTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandHandlerRegistry salCommandHandlerRegistry() {
        return new CommandHandlerRegistry();
    }

    @Bean
    public static CommandHandlerBeanPostProcessor salCommandHandlerBeanPostProcessor(
            CommandHandlerRegistry registry, WireTypeRegistry wireRegistry) {
        return new CommandHandlerBeanPostProcessor(registry, wireRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseBuilder salResponseBuilder(RecordedMessageConverter converter,
                                               SalCommandsProperties props) {
        return new ResponseBuilder(converter.mapper(), props.adapterName());
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandListener salCommandListener(CommandHandlerRegistry registry,
                                               RecordedMessageConverter converter,
                                               ResponseBuilder responseBuilder,
                                               CommandTransport transport) {
        return new CommandListener(registry, converter, responseBuilder, transport);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResultListener salResultListener(RecordedMessageConverter converter,
                                             CorrelationStore store,
                                             ExceptionMapper exceptionMapper) {
        return new ResultListener(converter, store, exceptionMapper);
    }

    @Bean
    public Queue salAdapterQueue(SalCommandsProperties props) {
        String queueName = "cmd." + props.adapterName();
        log.info("[SAL] Declaring adapter queue: '{}'", queueName);
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Declarables salExchangesAndResultBindings(SalCommandsProperties props) {
        Queue queue = salAdapterQueue(props);
        DirectExchange completed = new DirectExchange(
            "TCB.Infrastructure.Command.CommandCompletedEvent", true, false);
        DirectExchange failed = new DirectExchange(
            "TCB.Infrastructure.Command.CommandFailedEvent", true, false);
        DirectExchange command = new DirectExchange("CommandExchange", true, false);

        return new Declarables(
            completed, failed, command, queue,
            BindingBuilder.bind(queue).to(completed).with(props.adapterName()),
            BindingBuilder.bind(queue).to(failed).with(props.adapterName()));
    }

    @Bean
    @ConditionalOnMissingBean(name = "salResultListenerContainer")
    public SimpleMessageListenerContainer salResultListenerContainer(
            ConnectionFactory connectionFactory,
            ResultListener resultListener,
            RecordedMessageConverter converter,
            SalCommandsProperties props) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames("cmd." + props.adapterName());
        container.setMessageListener(message -> {
            var amqpMsg = converter.fromSpringMessage(message);
            String exchange = message.getMessageProperties().getReceivedExchange();
            if ("CommandExchange".equals(exchange)) {
                // This is not ideal — in a proper setup, command and result queues would be separate.
                // For now, we detect by exchange name.
            } else {
                resultListener.onMessage(amqpMsg);
            }
        });
        container.setConcurrentConsumers(Integer.parseInt(props.concurrency()));
        return container;
    }

    /**
     * Dispatch container for incoming commands. Queues and listener bindings are
     * wired up post-factum by {@link SalCommandDispatchBootstrap} once all beans
     * (and therefore all @CommandHandler registrations) are initialized.
     */
    @Bean
    @ConditionalOnMissingBean(name = "salCommandDispatchContainer")
    public SimpleMessageListenerContainer salCommandDispatchContainer(
            ConnectionFactory connectionFactory,
            SalCommandsProperties props) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setAutoStartup(false);
        container.setConcurrentConsumers(Integer.parseInt(props.concurrency()));
        return container;
    }

    @Bean
    public SalCommandDispatchBootstrap salCommandDispatchBootstrap(
            SimpleMessageListenerContainer salCommandDispatchContainer,
            CommandHandlerRegistry handlerRegistry,
            CommandListener commandListener,
            RecordedMessageConverter converter,
            AmqpAdmin amqpAdmin) {
        return new SalCommandDispatchBootstrap(
            salCommandDispatchContainer, handlerRegistry, commandListener, converter, amqpAdmin);
    }
}
