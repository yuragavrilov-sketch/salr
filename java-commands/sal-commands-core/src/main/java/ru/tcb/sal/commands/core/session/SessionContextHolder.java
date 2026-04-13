package ru.tcb.sal.commands.core.session;

import ru.tcb.sal.commands.api.SalSession;

public class SessionContextHolder {

    private static final ThreadLocal<SalSession> HOLDER = new ThreadLocal<>();

    public static SalSession current() {
        SalSession s = HOLDER.get();
        return s != null ? s : SalSession.empty();
    }

    public static AutoCloseable bind(SalSession session) {
        SalSession previous = HOLDER.get();
        HOLDER.set(session);
        return () -> {
            if (previous != null) HOLDER.set(previous);
            else HOLDER.remove();
        };
    }
}
