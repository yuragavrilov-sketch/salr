package ru.copperside.sal.commands.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandPriorityTest {

    @Test
    void byteValues_matchDotNetEnum() {
        assertThat(CommandPriority.IDLE.asByte()).isEqualTo((byte) 0);
        assertThat(CommandPriority.BELOW_NORMAL.asByte()).isEqualTo((byte) 4);
        assertThat(CommandPriority.NORMAL.asByte()).isEqualTo((byte) 5);
        assertThat(CommandPriority.ABOVE_NORMAL.asByte()).isEqualTo((byte) 6);
        assertThat(CommandPriority.HIGH.asByte()).isEqualTo((byte) 9);
        assertThat(CommandPriority.REAL_TIME.asByte()).isEqualTo((byte) 10);
    }

    @Test
    void fromByte_recoversEnum() {
        assertThat(CommandPriority.fromByte((byte) 5)).isEqualTo(CommandPriority.NORMAL);
        assertThat(CommandPriority.fromByte((byte) 10)).isEqualTo(CommandPriority.REAL_TIME);
    }

    @Test
    void fromByte_unknownValue_returnsNormal() {
        assertThat(CommandPriority.fromByte((byte) 77)).isEqualTo(CommandPriority.NORMAL);
    }

    @Test
    void fromByte_roundTrip_allValues() {
        for (CommandPriority p : CommandPriority.values()) {
            assertThat(CommandPriority.fromByte(p.asByte())).isEqualTo(p);
        }
    }
}
