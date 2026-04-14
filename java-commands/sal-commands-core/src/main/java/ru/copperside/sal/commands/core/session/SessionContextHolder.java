package ru.copperside.sal.commands.core.session;

import ru.copperside.sal.commands.api.SalSession;

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
