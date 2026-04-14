package ru.copperside.sal.commands.api;

import java.time.Duration;
import java.time.Instant;

public record CommandContext(
    String commandType,
    String correlationId,
    String sourceServiceId,
    Instant timestamp,
    String priority,
    String executionServiceId,
    Instant executionTimestamp,
    Duration executionDuration,
    String sessionId,
    String operationId
) {
    public static Duration parseDotNetTimeSpan(String ts) {
        if (ts == null || ts.isEmpty()) return Duration.ZERO;
        String[] parts = ts.split(":");
        if (parts.length != 3) return Duration.ZERO;
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        String[] secParts = parts[2].split("\\.");
        int seconds = Integer.parseInt(secParts[0]);
        long nanos = 0;
        if (secParts.length > 1) {
            String frac = secParts[1];
            while (frac.length() < 7) frac += "0";
            if (frac.length() > 7) frac = frac.substring(0, 7);
            nanos = Long.parseLong(frac) * 100;
        }
        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds).plusNanos(nanos);
    }
}
