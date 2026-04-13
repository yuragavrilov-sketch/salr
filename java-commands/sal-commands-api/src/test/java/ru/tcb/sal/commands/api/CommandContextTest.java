package ru.tcb.sal.commands.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class CommandContextTest {
    @Test
    void parseDotNetTimeSpan_realValue() {
        Duration d = CommandContext.parseDotNetTimeSpan("00:00:00.2392809");
        assertThat(d.toMillis()).isEqualTo(239);
        assertThat(d.toNanos() % 1_000_000).isEqualTo(280900L);
    }

    @Test
    void parseDotNetTimeSpan_zero() {
        assertThat(CommandContext.parseDotNetTimeSpan("00:00:00.0000000")).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseDotNetTimeSpan_nullOrEmpty() {
        assertThat(CommandContext.parseDotNetTimeSpan(null)).isEqualTo(Duration.ZERO);
        assertThat(CommandContext.parseDotNetTimeSpan("")).isEqualTo(Duration.ZERO);
    }
}
