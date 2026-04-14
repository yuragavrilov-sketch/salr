package ru.copperside.sal.commands.core.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.sal.commands.api.*;
import ru.copperside.sal.commands.api.annotation.ErrorPolicy;
import ru.copperside.sal.commands.api.annotation.WireName;
import ru.copperside.sal.commands.core.bus.CommandBusImpl;
import ru.copperside.sal.commands.core.bus.CorrelationStore;
import ru.copperside.sal.commands.core.handler.CommandHandlerRegistry;
import ru.copperside.sal.commands.core.handler.HandlerBinding;
import ru.copperside.sal.commands.core.exception.ExceptionMapper;
import ru.copperside.sal.commands.core.listener.CommandListener;
import ru.copperside.sal.commands.core.listener.ResponseBuilder;
import ru.copperside.sal.commands.core.listener.ResultListener;
import ru.copperside.sal.commands.core.session.SessionSerializer;
import ru.copperside.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.copperside.sal.commands.core.wire.WireTypeRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCommandTransportTest {

    @WireName(value = "TCB.Test.EchoCommand", assembly = "TCB.Test")
    public static class EchoCommand implements CommandWithResult<EchoResult> {
        public String message;
    }

    @WireName(value = "TCB.Test.EchoResult", assembly = "TCB.Test")
    public static class EchoResult implements CommandResult {
        public String echo;
    }

    // Handler method — will be called by CommandListener via MethodHandle
    public static EchoResult handleEcho(EchoCommand cmd) {
        EchoResult result = new EchoResult();
        result.echo = "ECHO: " + cmd.message;
        return result;
    }

    private CommandBus bus;

    @BeforeEach
    void setUp() throws Exception {
        WireTypeRegistry wireRegistry = new WireTypeRegistry();
        wireRegistry.register(EchoCommand.class);
        wireRegistry.register(EchoResult.class);

        RecordedMessageConverter converter = new RecordedMessageConverter(wireRegistry);
        SessionSerializer sessionSerializer = new SessionSerializer();
        CorrelationStore store = new CorrelationStore();
        ObjectMapper mapper = converter.mapper();

        InMemoryCommandTransport transport = new InMemoryCommandTransport();

        // Handler registry
        CommandHandlerRegistry handlerRegistry = new CommandHandlerRegistry();
        MethodHandle handle = MethodHandles.lookup().findStatic(
            InMemoryCommandTransportTest.class, "handleEcho",
            MethodType.methodType(EchoResult.class, EchoCommand.class));
        handlerRegistry.register(new HandlerBinding(
            "TCB.Test.EchoCommand", EchoCommand.class, EchoResult.class,
            null, handle, false, false, false, ErrorPolicy.REPLY_WITH_FAILURE));

        // Listeners
        ResponseBuilder responseBuilder = new ResponseBuilder(mapper, "TestAdapter");
        CommandListener commandListener = new CommandListener(
            handlerRegistry, converter, responseBuilder, transport);
        ResultListener resultListener = new ResultListener(converter, store, new ExceptionMapper());

        // Wire routing
        transport.registerConsumer("CommandExchange", commandListener::onMessage);
        transport.registerConsumer(
            "TCB.Infrastructure.Command.CommandCompletedEvent", resultListener::onMessage);
        transport.registerConsumer(
            "TCB.Infrastructure.Command.CommandFailedEvent", resultListener::onMessage);

        // CommandBus
        bus = new CommandBusImpl(transport, converter, sessionSerializer, store,
            "TestAdapter", Duration.ofSeconds(5));
    }

    @Test
    void fullRoundTrip_executeReturnsTypedResult() {
        EchoCommand cmd = new EchoCommand();
        cmd.message = "Hello World";

        EchoResult result = bus.execute(cmd);

        assertThat(result).isNotNull();
        assertThat(result.echo).isEqualTo("ECHO: Hello World");
    }

    @Test
    void fullRoundTrip_asyncWorks() throws Exception {
        EchoCommand cmd = new EchoCommand();
        cmd.message = "Async Test";

        EchoResult result = bus.executeAsync(cmd).get(5, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.echo).isEqualTo("ECHO: Async Test");
    }

    @Test
    void publish_fireAndForget_returnsCorrelationId() {
        EchoCommand cmd = new EchoCommand();
        cmd.message = "Fire";

        String cid = bus.publish(cmd);

        assertThat(cid).isNotEmpty();
    }
}
