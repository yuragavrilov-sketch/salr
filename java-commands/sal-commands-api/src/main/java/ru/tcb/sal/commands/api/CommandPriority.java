package ru.tcb.sal.commands.api;

/**
 * Соответствует .NET TCB.Infrastructure.Command.CommandPriority.
 * Байтовые значения — контракт с .NET-стороной, менять нельзя.
 */
public enum CommandPriority {
    IDLE((byte) 0),
    BELOW_NORMAL((byte) 4),
    NORMAL((byte) 5),
    ABOVE_NORMAL((byte) 6),
    HIGH((byte) 9),
    REAL_TIME((byte) 10);

    private final byte value;

    CommandPriority(byte value) {
        this.value = value;
    }

    public byte asByte() {
        return value;
    }

    public static CommandPriority fromByte(byte value) {
        for (CommandPriority p : values()) {
            if (p.value == value) return p;
        }
        return NORMAL;
    }
}
