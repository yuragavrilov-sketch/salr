package ru.tcb.sal.commands.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ru.tcb.sal.commands.api.CommandBus;
import ru.tcb.sal.commands.core.bus.CommandBusImpl;
import ru.tcb.sal.commands.core.bus.CorrelationStore;
import ru.tcb.sal.commands.core.exception.ExceptionMapper;
import ru.tcb.sal.commands.core.handler.CommandHandlerBeanPostProcessor;
import ru.tcb.sal.commands.core.handler.CommandHandlerRegistry;
import ru.tcb.sal.commands.core.listener.CommandListener;
import ru.tcb.sal.commands.core.listener.ResponseBuilder;
import ru.tcb.sal.commands.core.listener.ResultListener;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.CommandTransport;
import ru.tcb.sal.commands.core.transport.InMemoryCommandTransport;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.time.Duration;

/**
 * Test autoconfiguration that replaces RabbitMQ transport with InMemoryCommandTransport.
 * Used by @SalCommandsTest. All command execution happens in-process without network.
 */
@TestConfiguration
public class SalCommandsTestAutoConfiguration {

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
    public ExceptionMapper exceptionMapper() {
        return new ExceptionMapper();
    }

    @Bean
    public CorrelationStore correlationStore() {
        return new CorrelationStore();
    }

    @Bean
    public CommandHandlerRegistry commandHandlerRegistry() {
        return new CommandHandlerRegistry();
    }

    @Bean
    public static CommandHandlerBeanPostProcessor commandHandlerBeanPostProcessor(
            CommandHandlerRegistry registry, WireTypeRegistry wireRegistry) {
        return new CommandHandlerBeanPostProcessor(registry, wireRegistry);
    }

    @Bean
    public ResponseBuilder responseBuilder(RecordedMessageConverter converter) {
        return new ResponseBuilder(converter.mapper(), "TestAdapter");
    }

    @Bean
    @Primary
    public InMemoryCommandTransport inMemoryCommandTransport() {
        return new InMemoryCommandTransport();
    }

    @Bean
    public CommandListener commandListener(CommandHandlerRegistry registry,
                                            RecordedMessageConverter converter,
                                            ResponseBuilder responseBuilder,
                                            InMemoryCommandTransport transport) {
        return new CommandListener(registry, converter, responseBuilder, transport);
    }

    @Bean
    public ResultListener resultListener(RecordedMessageConverter converter,
                                          CorrelationStore store,
                                          ExceptionMapper exceptionMapper) {
        return new ResultListener(converter, store, exceptionMapper);
    }

    @Bean
    public CommandBus commandBus(InMemoryCommandTransport transport,
                                  RecordedMessageConverter converter,
                                  SessionSerializer sessionSerializer,
                                  CorrelationStore store,
                                  CommandListener commandListener,
                                  ResultListener resultListener) {
        // Wire in-memory routing
        transport.registerConsumer("CommandExchange", commandListener::onMessage);
        transport.registerConsumer("TCB.Infrastructure.Command.CommandCompletedEvent",
            resultListener::onMessage);
        transport.registerConsumer("TCB.Infrastructure.Command.CommandFailedEvent",
            resultListener::onMessage);

        return new CommandBusImpl(transport, converter, sessionSerializer, store,
            "TestAdapter", Duration.ofSeconds(5));
    }
}
