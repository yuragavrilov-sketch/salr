package ru.copperside.sal.commands.core.wire;

import org.junit.jupiter.api.Test;
import ru.copperside.sal.commands.api.Command;
import ru.copperside.sal.commands.api.CommandResult;
import ru.copperside.sal.commands.api.annotation.WireName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireTypeRegistryTest {

    @WireName("TCB.Test.PingCommand")
    static class PingCommand implements Command {}

    @WireName(value = "TCB.Test.PingResult", assembly = "TCB.Test.Contracts")
    static class PingResult implements CommandResult {}

    static class Unannotated implements Command {}

    @WireName("TCB.Test.ResultWithoutAssembly")
    static class ResultWithoutAssembly implements CommandResult {}

    @Test
    void register_knownCommand_resolvesBothWays() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);

        assertThat(registry.wireName(PingCommand.class)).isEqualTo("TCB.Test.PingCommand");
        assertThat(registry.classByWireName("TCB.Test.PingCommand")).isEqualTo(PingCommand.class);
    }

    @Test
    void register_result_producesAssemblyQualifiedName() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingResult.class);

        assertThat(registry.wireName(PingResult.class)).isEqualTo("TCB.Test.PingResult");
        assertThat(registry.assembly(PingResult.class)).isEqualTo("TCB.Test.Contracts");
        assertThat(registry.assemblyQualifiedName(PingResult.class))
            .isEqualTo("TCB.Test.PingResult, TCB.Test.Contracts");
    }

    @Test
    void register_commandWithoutAnnotation_fails() {
        WireTypeRegistry registry = new WireTypeRegistry();
        assertThatThrownBy(() -> registry.register(Unannotated.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@WireName")
            .hasMessageContaining("Unannotated");
    }

    @Test
    void register_resultWithoutAssembly_fails() {
        WireTypeRegistry registry = new WireTypeRegistry();
        assertThatThrownBy(() -> registry.register(ResultWithoutAssembly.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("assembly")
            .hasMessageContaining("ResultWithoutAssembly");
    }

    @Test
    void register_duplicateWireName_fails() {
        @WireName("TCB.Test.PingCommand")
        class DuplicatePing implements Command {}

        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);

        assertThatThrownBy(() -> registry.register(DuplicatePing.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void classByWireName_unknown_returnsNull() {
        WireTypeRegistry registry = new WireTypeRegistry();
        assertThat(registry.classByWireName("TCB.Test.Unknown")).isNull();
    }
}
