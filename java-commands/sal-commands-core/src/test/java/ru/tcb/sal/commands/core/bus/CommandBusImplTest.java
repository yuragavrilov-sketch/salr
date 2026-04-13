package ru.tcb.sal.commands.core.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.api.*;
import ru.tcb.sal.commands.api.annotation.WireName;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.CommandTransport;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBusImplTest {

    @WireName(value = "TCB.Test.PingCommand", assembly = "TCB.Test")
    public static class PingCommand implements CommandWithResult<PingResult> {
        public String text;
    }

    @WireName(value = "TCB.Test.PingResult", assembly = "TCB.Test")
    public static class PingResult implements CommandResult {
        public String echo;
    }

    private CommandBusImpl bus;
    private CorrelationStore store;
    private AtomicReference<RecordedMessageConverter.AmqpWireMessage> captured;

    @BeforeEach
    void setUp() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);
        registry.register(PingResult.class);

        RecordedMessageConverter converter = new RecordedMessageConverter(registry);
        SessionSerializer sessionSerializer = new SessionSerializer();
        store = new CorrelationStore();
        captured = new AtomicReference<>();

        CommandTransport mockTransport = msg -> {
            captured.set(msg);
            return CompletableFuture.completedFuture(null);
        };

        bus = new CommandBusImpl(
            mockTransport, converter, sessionSerializer, store,
            "TestAdapter", Duration.ofSeconds(30));
    }

    @Test
    void executeAsync_publishesWithCorrectWireFormat() {
        PingCommand cmd = new PingCommand();
        cmd.text = "hello";

        CompletableFuture<PingResult> future = bus.executeAsync(cmd);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().exchange()).isEqualTo("CommandExchange");
        assertThat(captured.get().routingKey()).isEqualTo("TCB.Test.PingCommand");
        assertThat(captured.get().contentType()).isEqualTo("TCB.Test.PingCommand, TCB.Test");
        assertThat(captured.get().correlationId()).isNotEmpty();
        assertThat(store.size()).isEqualTo(1);
        assertThat(future).isNotDone();
    }

    @Test
    void publish_fireAndForget_noCorrelation() {
        PingCommand cmd = new PingCommand();
        cmd.text = "fire";

        String cid = bus.publish(cmd);

        assertThat(cid).isNotEmpty();
        assertThat(captured.get()).isNotNull();
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void executeAsync_publishFailure_completesExceptionally() {
        CommandTransport failingTransport = msg ->
            CompletableFuture.failedFuture(new RuntimeException("RMQ down"));

        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);
        registry.register(PingResult.class);

        CommandBusImpl failBus = new CommandBusImpl(
            failingTransport, new RecordedMessageConverter(registry),
            new SessionSerializer(), new CorrelationStore(),
            "Test", Duration.ofSeconds(5));

        PingCommand cmd = new PingCommand();
        CompletableFuture<PingResult> future = failBus.executeAsync(cmd);

        assertThat(future).isCompletedExceptionally();
    }
}
