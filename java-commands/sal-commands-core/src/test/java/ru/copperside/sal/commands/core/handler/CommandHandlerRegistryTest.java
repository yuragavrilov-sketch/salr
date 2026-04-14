package ru.copperside.sal.commands.core.handler;

import org.junit.jupiter.api.Test;
import ru.copperside.sal.commands.api.Command;
import ru.copperside.sal.commands.api.CommandResult;
import ru.copperside.sal.commands.api.annotation.ErrorPolicy;
import ru.copperside.sal.commands.api.annotation.WireName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandHandlerRegistryTest {

    @WireName("TCB.Test.PingCommand")
    static class PingCmd implements Command {}
    static class PingRes implements CommandResult {}

    @Test
    void register_and_find() {
        CommandHandlerRegistry registry = new CommandHandlerRegistry();
        HandlerBinding binding = new HandlerBinding("TCB.Test.PingCommand",
            PingCmd.class, PingRes.class, new Object(), null,
            false, false, false, ErrorPolicy.REPLY_WITH_FAILURE);

        registry.register(binding);

        assertThat(registry.find("TCB.Test.PingCommand")).isNotNull();
        assertThat(registry.find("TCB.Test.PingCommand").commandClass()).isEqualTo(PingCmd.class);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void register_duplicate_throws() {
        CommandHandlerRegistry registry = new CommandHandlerRegistry();
        HandlerBinding b1 = new HandlerBinding("TCB.Test.Dup", PingCmd.class, null,
            new Object(), null, false, false, false, ErrorPolicy.REPLY_WITH_FAILURE);
        HandlerBinding b2 = new HandlerBinding("TCB.Test.Dup", PingCmd.class, null,
            new Object(), null, false, false, false, ErrorPolicy.REPLY_WITH_FAILURE);

        registry.register(b1);
        assertThatThrownBy(() -> registry.register(b2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void find_unknown_returnsNull() {
        assertThat(new CommandHandlerRegistry().find("unknown")).isNull();
    }
}
